package com.anthill.binance.config

import com.anthill.binance.instruments.InstrumentRefresher
import com.anthill.binance.rest.BinanceRestClient
import com.anthill.binance.rest.HttpBinanceRestClient
import com.anthill.binance.rest.WeightBudget
import com.anthill.binance.stream.BinanceStreamClient
import com.anthill.binance.stream.DayBook
import com.anthill.binance.stream.StreamEventBridge
import com.anthill.binance.stream.StreamHeartbeat
import com.anthill.contract.ContractJson
import com.anthill.eventbus.EventPublisher
import com.anthill.eventbus.HttpEventTransport
import com.anthill.eventbus.Subscriber
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.JacksonModule
import java.net.URI
import java.time.Clock
import java.util.logging.Level
import java.util.logging.Logger

@ConfigurationProperties("anthill.binance")
data class BinanceProperties(
    val restBaseUrl: String,
    val wsUrl: String,
    val weightPerMinute: Int = 4800,
    val maxStreams: Int = 1000,
)

@ConfigurationProperties("anthill.events")
data class EventsProperties(val enabled: Boolean = true, val subscribers: List<SubscriberProperties> = emptyList()) {
    data class SubscriberProperties(val types: List<String>, val url: URI)
}

@Configuration
@EnableConfigurationProperties(BinanceProperties::class, EventsProperties::class)
class AdapterConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun contractJsonModule(): JacksonModule = ContractJson.contractModule()

    @Bean
    fun weightBudget(props: BinanceProperties) = WeightBudget(props.weightPerMinute)

    @Bean
    fun binanceRestClient(props: BinanceProperties, budget: WeightBudget, clock: Clock): BinanceRestClient =
        HttpBinanceRestClient(props.restBaseUrl, budget, clock, props.maxStreams)

    @Bean
    fun eventPublisher(props: EventsProperties, clock: Clock, meter: MeterRegistry): EventPublisher =
        if (!props.enabled) {
            EventPublisher { _, _, _ -> }
        } else {
            HttpEventTransport(
                "binance-adapter", props.subscribers.map { Subscriber(it.types, it.url) }, ContractJson.mapper(), clock,
                onDropped = { meter.counter("anthill.events.dropped").increment(it.toDouble()) },
            )
        }

    @Bean
    fun dayBook() = DayBook()

    @Bean
    fun streamEventBridge(publisher: EventPublisher, dayBook: DayBook) = StreamEventBridge(publisher, dayBook)

    @Bean
    fun binanceStreamClient(props: BinanceProperties, bridge: StreamEventBridge, clock: Clock) =
        BinanceStreamClient(bridge, clock, BinanceStreamClient.connector(props.wsUrl))

    @Bean
    fun instrumentRefresher(rest: BinanceRestClient, stream: BinanceStreamClient, publisher: EventPublisher) =
        InstrumentRefresher(rest, stream, publisher)

    @Bean
    fun dayBookAnchor(rest: BinanceRestClient, refresher: InstrumentRefresher, dayBook: DayBook) = DayBookAnchor(rest, refresher, dayBook)

    /**
     * 종목 목록을 받고 → 스트림을 시작하고 → 당일 일봉을 REST 값으로 앵커한다. 바이낸스에 닿지 않으면 5초마다 다시 시도하고,
     * 앱 기동은 막지 않는다. 스트림을 먼저 열어 두어야 앵커 직후의 초를 놓치지 않는다(DayBook이 최근 초를 다시 더함).
     */
    @Bean
    @ConditionalOnProperty("anthill.stream.enabled", havingValue = "true")
    fun streamStarter(refresher: InstrumentRefresher, stream: BinanceStreamClient, anchor: DayBookAnchor) = ApplicationRunner {
        Thread.ofVirtual().name("stream-starter").start {
            val log = Logger.getLogger("stream-starter")
            retry(log, "바이낸스 종목 목록을 받지 못해 5초 뒤 다시 시도합니다") {
                refresher.refresh()
                stream.start(refresher.currentSymbols())
                log.info("바이낸스 스트림 시작: ${refresher.currentSymbols().size}종목")
            }
            retry(log, "바이낸스 당일 시세를 받지 못해 5초 뒤 다시 시도합니다") {
                anchor.amounts()
                anchor.anchor()
            }
        }
    }

    private fun retry(log: Logger, message: String, block: () -> Unit) {
        while (true) {
            try {
                block()
                return
            } catch (e: Exception) {
                log.log(Level.WARNING, message, e)
                Thread.sleep(5000)
            }
        }
    }
}

/** 당일 일봉 앵커와 24시간 거래대금 갱신. */
class DayBookAnchor(private val rest: BinanceRestClient, private val refresher: InstrumentRefresher, private val dayBook: DayBook) {
    fun anchor() {
        val symbols = refresher.currentSymbols()
        if (symbols.isEmpty()) return
        dayBook.anchor(rest.tradingDays(symbols))
    }

    fun amounts() = dayBook.setAmounts(rest.amounts24h())
}

/** 5초마다 스트림 상태를 다시 알린다(marketdata가 어댑터가 살아 있는지 판단한다). 1분마다 받은 양을 로그로 남긴다. */
@Component
@ConditionalOnProperty("anthill.stream.enabled", havingValue = "true")
class StreamHeartbeatSchedule(private val stream: BinanceStreamClient, publisher: EventPublisher, clock: Clock, private val bridge: StreamEventBridge) {
    private val log = Logger.getLogger(StreamHeartbeatSchedule::class.java.name)
    private val heartbeat = StreamHeartbeat(stream, publisher, clock)

    @Scheduled(fixedRate = 5000)
    fun beat() = heartbeat.beat()

    @Scheduled(initialDelay = 60_000, fixedRate = 60_000)
    fun logReceived() {
        val n = bridge.drainReceived()
        log.info("지난 1분 동안 바이낸스 1초 캔들 ${n}개 받음(초당 ${n / 60}개), 연결: ${stream.status.connected}")
    }
}

/** 정기 작업: 종목 목록 10분, 당일 일봉 다시 앵커 10분, 24시간 거래대금 60초. */
@Component
@ConditionalOnProperty("anthill.stream.enabled", havingValue = "true")
class RefreshSchedule(private val refresher: InstrumentRefresher, private val anchor: DayBookAnchor) {
    private val log = Logger.getLogger(RefreshSchedule::class.java.name)

    @Scheduled(initialDelay = 600_000, fixedDelay = 600_000)
    fun instruments() = quietly("종목 목록 갱신 실패") { refresher.refresh() }

    @Scheduled(initialDelay = 600_000, fixedDelay = 600_000)
    fun reanchor() = quietly("당일 일봉 다시 앵커 실패") { anchor.anchor() }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    fun amounts() = quietly("24시간 거래대금 갱신 실패") { anchor.amounts() }

    private fun quietly(message: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.log(Level.WARNING, message, e)
        }
    }
}
