package com.anthill.marketdata.config

import com.anthill.contract.ContractJson
import com.anthill.eventbus.EventHandler
import com.anthill.eventbus.EventInbox
import com.anthill.eventbus.EventPublisher
import com.anthill.eventbus.HttpEventTransport
import com.anthill.eventbus.Subscriber
import com.anthill.marketdata.candle.CandleRepository
import com.anthill.marketdata.candle.CandleSync
import com.anthill.marketdata.candle.CoverageRepository
import com.anthill.marketdata.candle.InstrumentRepository
import com.anthill.marketdata.ingest.InstrumentHandler
import com.anthill.marketdata.ingest.SecondCandleHandler
import com.anthill.marketdata.ingest.SourceCandleHandler
import com.anthill.marketdata.ingest.StreamStatusHandler
import com.anthill.marketdata.ingest.TickerHandler
import com.anthill.marketdata.ingest.TradeHandler
import com.anthill.marketdata.ingest.ViewingHandler
import com.anthill.marketdata.live.ClosedCandlePersister
import com.anthill.marketdata.live.LiveCandleBook
import com.anthill.marketdata.live.LiveIngest
import com.anthill.marketdata.live.LivePublisher
import com.anthill.marketdata.live.SecondRebuilder
import com.anthill.marketdata.live.StreamStatusTracker
import com.anthill.marketdata.live.TickerCache
import com.anthill.marketdata.live.ViewingTracker
import com.anthill.marketdata.source.HttpSourceClient
import com.anthill.marketdata.source.SourceClient
import com.anthill.marketdata.sync.InitialBackfill
import com.anthill.marketdata.sync.OutageLog
import com.anthill.marketdata.sync.ReconcileLoops
import com.anthill.marketdata.sync.ReconcilePlanner
import com.anthill.marketdata.sync.Reconciler
import com.anthill.marketdata.sync.Recovery
import com.anthill.marketdata.sync.Startup
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import com.anthill.marketdata.candle.CandleChangeSink
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
import java.time.Duration
import java.util.concurrent.Executors

@ConfigurationProperties("anthill")
data class SourcesProperties(val sources: Map<String, URI> = emptyMap())

@ConfigurationProperties("anthill.events")
data class EventsProperties(val enabled: Boolean = true, val subscribers: List<SubscriberProperties> = emptyList()) {
    data class SubscriberProperties(val types: List<String>, val url: URI)
}

@ConfigurationProperties("anthill.live")
data class LiveProperties(val closeGrace: Duration = Duration.ofSeconds(10))

/** 소스별 정합성 검사 동시 처리 수. 적지 않은 소스는 1. */
@ConfigurationProperties("anthill.reconcile")
data class ReconcileProperties(val parallelism: Map<String, Int> = emptyMap())

@Configuration
@EnableConfigurationProperties(SourcesProperties::class, EventsProperties::class, LiveProperties::class, BackfillProperties::class, ReconcileProperties::class)
class MarketdataConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun contractJsonModule(): JacksonModule = ContractJson.contractModule()

    @Bean
    fun sourceClient(props: SourcesProperties): SourceClient = HttpSourceClient(props.sources, ContractJson.mapper())

    @Bean
    fun eventPublisher(props: EventsProperties, clock: Clock, meter: MeterRegistry): EventPublisher =
        if (!props.enabled) {
            EventPublisher { _, _, _ -> }
        } else {
            HttpEventTransport(
                "marketdata-service", props.subscribers.map { Subscriber(it.types, it.url) }, ContractJson.mapper(), clock,
                onDropped = { meter.counter("anthill.events.dropped").increment(it.toDouble()) },
            )
        }

    @Bean
    fun closedCandlePersister(candles: CandleRepository, coverage: CoverageRepository, publisher: ObjectProvider<LivePublisher>) =
        ClosedCandlePersister(
            candles, coverage, Executors.newSingleThreadExecutor(Thread.ofVirtual().name("persist-closed").factory()),
            // 늦게 온 값을 저장하면 화면에 정정으로 보낸다. LivePublisher → book → persister 순환을 지연 조회로 끊는다.
            CandleChangeSink { i, u, up, del -> publisher.getObject().onCorrected(i, u, up, del) },
        )

    /** 1초 캔들이 끊기면 그 종목을 다시 맞춘다. book → rebuilder → book 순환을 지연 조회로 끊는다(persister와 같은 방식). */
    @Bean
    fun liveCandleBook(clock: Clock, props: LiveProperties, persister: ClosedCandlePersister, rebuilder: ObjectProvider<SecondRebuilder>) =
        LiveCandleBook(clock, props.closeGrace, onSecondGap = { i, cut -> rebuilder.getObject().request(i, cut) }, sink = persister)

    @Bean
    fun secondRebuilder(source: SourceClient, book: LiveCandleBook) = SecondRebuilder(source, book::applyRebuild)

    @Bean
    fun tickerCache() = TickerCache()

    @Bean
    fun streamStatusTracker(clock: Clock) = StreamStatusTracker(clock.instant())

    @Bean
    fun viewingTracker(clock: Clock) = ViewingTracker(clock)

    @Bean
    fun liveIngest(book: LiveCandleBook, tracker: StreamStatusTracker) = LiveIngest(book, tracker)

    @Bean
    fun livePublisher(book: LiveCandleBook, tickers: TickerCache, publisher: EventPublisher, clock: Clock) =
        LivePublisher(book, tickers, publisher, clock)

    @Bean
    fun eventInbox(
        book: LiveCandleBook, tickers: TickerCache, tracker: StreamStatusTracker, viewing: ViewingTracker,
        instruments: InstrumentRepository, clock: Clock,
    ): EventInbox {
        val handlers: List<EventHandler<*>> = listOf(
            TradeHandler(book, tracker), SourceCandleHandler(book, tracker), SecondCandleHandler(book, tracker), TickerHandler(book, tickers, tracker),
            StreamStatusHandler(tracker),
            InstrumentHandler(instruments, clock), ViewingHandler(viewing),
        )
        return EventInbox(handlers, ContractJson.mapper())
    }

    @Bean
    fun startup(
        props: SourcesProperties, source: SourceClient, instruments: InstrumentRepository, tickers: TickerCache, book: LiveCandleBook,
        tracker: StreamStatusTracker, sync: CandleSync, candles: CandleRepository, clock: Clock, outages: OutageLog, coverage: CoverageRepository,
    ) = Startup(props.sources.keys.toList(), source, instruments, tickers, book, tracker, sync, candles, clock, outages, coverage)

    @Bean
    fun reconciler(
        sync: CandleSync, coverage: CoverageRepository, instruments: InstrumentRepository, tickers: TickerCache,
        viewing: ViewingTracker, outages: OutageLog, meter: MeterRegistry, clock: Clock, reconcile: ReconcileProperties,
    ) = Reconciler(ReconcilePlanner(), sync, coverage, instruments, tickers, viewing, outages, meter, clock, reconcile.parallelism)

    /** 소스마다 정합성 검사 반복(5초 고정 지연). 앱이 내려가면 Spring이 close()로 멈춘다. */
    @Bean
    @ConditionalOnProperty("anthill.jobs.enabled", havingValue = "true")
    fun reconcileLoops(props: SourcesProperties, reconciler: Reconciler) = ReconcileLoops(props.sources.keys.toList(), reconciler).also { it.start() }

    @Bean
    fun initialBackfill(
        instruments: InstrumentRepository, coverage: CoverageRepository, sync: CandleSync, tickers: TickerCache, clock: Clock, backfill: BackfillProperties,
    ) = InitialBackfill(instruments, coverage, sync, tickers, clock, backfill.minuteDays)

    @Bean
    fun recovery(
        instruments: InstrumentRepository, coverage: CoverageRepository, sync: CandleSync, reconciler: Reconciler,
        backfill: InitialBackfill, outages: OutageLog, clock: Clock,
    ) = Recovery(instruments, coverage, sync, reconciler, backfill, outages, clock)

    /**
     * 시작: 스트림 연결 → 복구, 끊김 → 장애 구간 열기를 걸고, 소스마다 따로 준비 → 복구(→ 과거 채우기)를 한다.
     * 한 소스(예: 바이낸스 어댑터)가 안 떠도 다른 소스의 복구는 진행된다.
     */
    @Bean
    @ConditionalOnProperty("anthill.jobs.enabled", havingValue = "true")
    fun startupRunner(startup: Startup, tracker: StreamStatusTracker, recovery: Recovery, outages: OutageLog) = ApplicationRunner {
        tracker.onDisconnected { source, at -> outages.open(source, at) }
        tracker.onConnected { source, _ -> Thread.ofVirtual().name("recovery-$source").start { recovery.recoverAll(source) } }
        // 시작 복구는 연결 알림에 기대지 않고 직접 부른다(리스너를 걸기 전에 알림이 먼저 올 수 있다).
        Thread.ofVirtual().name("startup").start {
            startup.run { source -> recovery.recoverAll(source) }
        }
    }
}

@ConfigurationProperties("anthill.backfill")
data class BackfillProperties(val minuteDays: Int = 7)

@Component
@ConditionalOnProperty("anthill.jobs.enabled", havingValue = "true")
class LiveTickSchedule(
    private val publisher: LivePublisher,
    private val tracker: StreamStatusTracker,
    private val clock: Clock,
) {
    @Scheduled(fixedRate = 1000)
    fun tick() = publisher.tick()

    /** 어댑터는 5초마다 상태를 알린다. 20초 동안 아무 소식이 없으면 끊긴 것으로 보고 장애 구간을 연다(리뷰 I5). */
    @Scheduled(fixedRate = 5000)
    fun watchdog() = tracker.expire(clock.instant(), Duration.ofSeconds(20))
}
