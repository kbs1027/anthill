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
import com.anthill.marketdata.ingest.SourceCandleHandler
import com.anthill.marketdata.ingest.StreamStatusHandler
import com.anthill.marketdata.ingest.TickerHandler
import com.anthill.marketdata.ingest.TradeHandler
import com.anthill.marketdata.ingest.ViewingHandler
import com.anthill.marketdata.leader.LeaderGatedSink
import com.anthill.marketdata.leader.LeaderLease
import com.anthill.marketdata.leader.Leadership
import com.anthill.marketdata.live.ClosedCandlePersister
import com.anthill.marketdata.live.LiveCandleBook
import com.anthill.marketdata.live.LiveIngest
import com.anthill.marketdata.live.LivePublisher
import com.anthill.marketdata.live.StreamStatusTracker
import com.anthill.marketdata.live.TickerCache
import com.anthill.marketdata.live.ViewingTracker
import com.anthill.marketdata.source.HttpSourceClient
import com.anthill.marketdata.source.SourceClient
import com.anthill.marketdata.sync.InitialBackfill
import com.anthill.marketdata.sync.OutageLog
import com.anthill.marketdata.sync.ReconcilePlanner
import com.anthill.marketdata.sync.Reconciler
import com.anthill.marketdata.sync.Recovery
import com.anthill.marketdata.sync.Startup
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
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
import java.util.UUID
import java.util.concurrent.Executors

@ConfigurationProperties("anthill")
data class SourcesProperties(val sources: Map<String, List<URI>> = emptyMap())

@ConfigurationProperties("anthill.events")
data class EventsProperties(val enabled: Boolean = true, val subscribers: List<SubscriberProperties> = emptyList()) {
    data class SubscriberProperties(val types: List<String>, val url: URI)
}

@ConfigurationProperties("anthill.live")
data class LiveProperties(val closeGrace: Duration = Duration.ofSeconds(10))

@Configuration
@EnableConfigurationProperties(SourcesProperties::class, EventsProperties::class, LiveProperties::class, BackfillProperties::class)
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

    /** 작업이 꺼진 프로필(검증 도구·테스트)은 임차권 없이 언제나 주 서버다. */
    @Bean
    fun leadership(
        jdbc: JdbcClient, @Value("\${anthill.jobs.enabled:false}") jobs: Boolean, @Value("\${anthill.instance-id:}") instanceId: String,
        @Value("\${anthill.leader.ttl:1s}") ttl: Duration,
    ): Leadership = if (jobs) Leadership(LeaderLease(jdbc), instanceId.ifBlank { UUID.randomUUID().toString() }, ttl) else Leadership.always()

    @Bean
    fun leaderGatedSink(persister: ClosedCandlePersister, leadership: Leadership, clock: Clock) = LeaderGatedSink(persister, leadership::isLeader, clock)

    @Bean
    fun liveCandleBook(clock: Clock, props: LiveProperties, sink: LeaderGatedSink) = LiveCandleBook(clock, props.closeGrace, sink)

    @Bean
    fun tickerCache() = TickerCache()

    @Bean
    fun streamStatusTracker(clock: Clock) = StreamStatusTracker(clock.instant())

    @Bean
    fun viewingTracker(clock: Clock) = ViewingTracker(clock)

    @Bean
    fun liveIngest(book: LiveCandleBook, tracker: StreamStatusTracker) = LiveIngest(book, tracker)

    @Bean
    fun livePublisher(
        book: LiveCandleBook, tickers: TickerCache, publisher: EventPublisher, clock: Clock, leadership: Leadership, tracker: StreamStatusTracker, sources: SourcesProperties,
    ) = LivePublisher(book, tickers, publisher, clock, leadership::isLeader, { sources.sources.keys.all { tracker.connectedSince(it) != null } })

    @Bean
    fun eventInbox(
        book: LiveCandleBook, tickers: TickerCache, tracker: StreamStatusTracker, viewing: ViewingTracker,
        instruments: InstrumentRepository, clock: Clock,
    ): EventInbox {
        val handlers: List<EventHandler<*>> = listOf(
            TradeHandler(book, tracker), SourceCandleHandler(book, tracker), TickerHandler(book, tickers, tracker), StreamStatusHandler(tracker),
            InstrumentHandler(instruments, clock), ViewingHandler(viewing),
        )
        return EventInbox(handlers, ContractJson.mapper())
    }

    @Bean
    fun startup(
        props: SourcesProperties, source: SourceClient, instruments: InstrumentRepository, tickers: TickerCache, book: LiveCandleBook,
        tracker: StreamStatusTracker, sync: CandleSync, candles: CandleRepository, clock: Clock, outages: OutageLog, coverage: CoverageRepository,
        leadership: Leadership,
    ) = Startup(props.sources.keys.toList(), source, instruments, tickers, book, tracker, sync, candles, clock, outages, coverage, isLeader = leadership::isLeader)

    @Bean
    fun reconciler(
        sync: CandleSync, coverage: CoverageRepository, instruments: InstrumentRepository, tickers: TickerCache,
        viewing: ViewingTracker, outages: OutageLog, meter: MeterRegistry, clock: Clock,
    ) = Reconciler(ReconcilePlanner(), sync, coverage, instruments, tickers, viewing, outages, meter, clock)

    @Bean
    fun initialBackfill(
        instruments: InstrumentRepository, coverage: CoverageRepository, sync: CandleSync, tickers: TickerCache, clock: Clock, backfill: BackfillProperties,
    ) = InitialBackfill(instruments, coverage, sync, tickers, clock, backfill.minuteDays)

    @Bean
    fun recovery(
        instruments: InstrumentRepository, coverage: CoverageRepository, sync: CandleSync, reconciler: Reconciler,
        backfill: InitialBackfill, outages: OutageLog, clock: Clock,
    ) = Recovery(instruments, coverage, sync, reconciler, backfill, outages, clock)

    /** 시작: 스트림 연결 → 복구, 끊김 → 장애 구간 열기를 걸고, 소스 준비 뒤 복구한다. */
    @Bean
    @ConditionalOnProperty("anthill.jobs.enabled", havingValue = "true")
    fun startupRunner(
        startup: Startup, tracker: StreamStatusTracker, recovery: Recovery, outages: OutageLog, props: SourcesProperties,
        leadership: Leadership, sink: LeaderGatedSink, live: LivePublisher,
    ) = ApplicationRunner {
        tracker.onDisconnected { source, at -> if (leadership.isLeader()) outages.open(source, at) }
        tracker.onConnected { source, _ -> if (leadership.isLeader()) Thread.ofVirtual().name("recovery-$source").start { recovery.recoverAll(source) } }
        leadership.tick()
        // 예비가 이어받으면: 들고 있던 마감을 저장·전송하고 바로 한 번 발행한 뒤, 그 사이 빈 구간이 있는지 복구로 확인한다.
        leadership.onPromoted {
            sink.flush()
            live.onPromoted()
            live.tick()
            Thread.ofVirtual().name("promoted").start { props.sources.keys.forEach { recovery.recoverAll(it) } }
        }
        // 시작 복구는 연결 알림에 기대지 않고 직접 부른다(리스너를 걸기 전에 알림이 먼저 올 수 있다).
        Thread.ofVirtual().name("startup").start {
            startup.run()
            if (leadership.isLeader()) props.sources.keys.forEach { recovery.recoverAll(it) }
        }
    }
}

@ConfigurationProperties("anthill.backfill")
data class BackfillProperties(val minuteDays: Int = 7)

@Component
@ConditionalOnProperty("anthill.jobs.enabled", havingValue = "true")
class LiveTickSchedule(
    private val publisher: LivePublisher,
    private val reconciler: Reconciler,
    private val tracker: StreamStatusTracker,
    private val clock: Clock,
    private val leadership: Leadership,
) {
    @Scheduled(fixedRate = 1000)
    fun tick() = publisher.tick()

    /** 임차권 유효 시간(1초)의 4분의 1마다 갱신한다. 주 서버가 죽으면 예비가 1초 남짓 안에 이어받는다. */
    @Scheduled(fixedRate = 250)
    fun lease() = leadership.tick()

    /** 어댑터는 1초마다 상태를 알린다. 3초 동안 아무 소식이 없으면 그 어댑터는 끊긴 것으로 본다(마지막 한 대면 장애 구간을 연다). */
    @Scheduled(fixedRate = 1000)
    fun watchdog() = tracker.expire(clock.instant(), Duration.ofSeconds(3))

    @Scheduled(initialDelay = 5000, fixedDelay = 5000)
    fun reconcile() { if (leadership.isLeader()) reconciler.runDue() }
}
