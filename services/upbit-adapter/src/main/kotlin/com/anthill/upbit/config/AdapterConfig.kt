package com.anthill.upbit.config

import com.anthill.contract.ContractJson
import com.anthill.eventbus.EventPublisher
import com.anthill.eventbus.HttpEventTransport
import com.anthill.eventbus.Subscriber
import com.anthill.upbit.instruments.InstrumentRefresher
import com.anthill.upbit.rest.HttpUpbitRestClient
import com.anthill.upbit.rest.RequestBudget
import com.anthill.upbit.rest.UpbitRestClient
import com.anthill.upbit.stream.StreamEventBridge
import com.anthill.upbit.stream.StreamHeartbeat
import com.anthill.upbit.stream.UpbitStreamClient
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

@ConfigurationProperties("anthill.upbit")
data class UpbitProperties(val restBaseUrl: String, val wsUrl: String, val restRatePerSecond: Int = 9)

@ConfigurationProperties("anthill.events")
data class EventsProperties(val enabled: Boolean = true, val subscribers: List<SubscriberProperties> = emptyList()) {
    data class SubscriberProperties(val types: List<String>, val url: URI)
}

@Configuration
@EnableConfigurationProperties(UpbitProperties::class, EventsProperties::class)
class AdapterConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun contractJsonModule(): JacksonModule = ContractJson.contractModule()

    @Bean
    fun requestBudget(props: UpbitProperties) = RequestBudget(props.restRatePerSecond)

    @Bean
    fun upbitRestClient(props: UpbitProperties, budget: RequestBudget): UpbitRestClient =
        HttpUpbitRestClient(props.restBaseUrl, budget)

    @Bean
    fun eventPublisher(props: EventsProperties, clock: Clock, meter: MeterRegistry): EventPublisher =
        if (!props.enabled) {
            EventPublisher { _, _, _ -> }
        } else {
            HttpEventTransport(
                "upbit-adapter", props.subscribers.map { Subscriber(it.types, it.url) }, ContractJson.mapper(), clock,
                onDropped = { meter.counter("anthill.events.dropped").increment(it.toDouble()) },
            )
        }

    @Bean
    fun streamEventBridge(publisher: EventPublisher) = StreamEventBridge(publisher)

    @Bean
    fun upbitStreamClient(props: UpbitProperties, bridge: StreamEventBridge, clock: Clock) =
        UpbitStreamClient(bridge, clock, UpbitStreamClient.connector(props.wsUrl))

    @Bean
    fun instrumentRefresher(rest: UpbitRestClient, stream: UpbitStreamClient, publisher: EventPublisher) =
        InstrumentRefresher(rest, stream, publisher)

    /** 종목 목록을 받은 뒤 스트림을 시작한다. 업비트에 닿지 않으면 5초마다 다시 시도하고, 앱 기동은 막지 않는다. */
    @Bean
    @ConditionalOnProperty("anthill.stream.enabled", havingValue = "true")
    fun streamStarter(refresher: InstrumentRefresher, stream: UpbitStreamClient) = ApplicationRunner {
        Thread.ofVirtual().name("stream-starter").start {
            while (true) {
                try {
                    refresher.refresh()
                    stream.start(refresher.currentMarkets())
                    return@start
                } catch (e: Exception) {
                    Logger.getLogger("stream-starter").log(Level.WARNING, "instrument fetch failed, retrying", e)
                    Thread.sleep(5000)
                }
            }
        }
    }
}

/** 5초마다 스트림 상태를 다시 알린다(marketdata가 어댑터가 살아 있는지 판단한다). */
@Component
@ConditionalOnProperty("anthill.stream.enabled", havingValue = "true")
class StreamHeartbeatSchedule(stream: UpbitStreamClient, publisher: EventPublisher, clock: Clock) {
    private val heartbeat = StreamHeartbeat(stream, publisher, clock)

    @Scheduled(fixedRate = 5000)
    fun beat() = heartbeat.beat()
}

@Component
@ConditionalOnProperty("anthill.stream.enabled", havingValue = "true")
class InstrumentRefreshSchedule(private val refresher: InstrumentRefresher) {
    private val log = Logger.getLogger(InstrumentRefreshSchedule::class.java.name)

    @Scheduled(initialDelay = 600_000, fixedDelay = 600_000)
    fun refresh() {
        try {
            refresher.refresh()
        } catch (e: Exception) {
            log.log(Level.WARNING, "instrument refresh failed", e)
        }
    }
}
