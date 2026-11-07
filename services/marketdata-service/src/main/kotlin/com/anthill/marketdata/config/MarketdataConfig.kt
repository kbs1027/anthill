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
import com.anthill.marketdata.live.ClosedCandlePersister
import com.anthill.marketdata.live.LiveCandleBook
import com.anthill.marketdata.live.LiveIngest
import com.anthill.marketdata.live.LivePublisher
import com.anthill.marketdata.live.StreamStatusTracker
import com.anthill.marketdata.live.TickerCache
import com.anthill.marketdata.live.ViewingTracker
import com.anthill.marketdata.source.HttpSourceClient
import com.anthill.marketdata.source.SourceClient
import com.anthill.marketdata.sync.Startup
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

@Configuration
@EnableConfigurationProperties(SourcesProperties::class, EventsProperties::class, LiveProperties::class)
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
    fun closedCandlePersister(candles: CandleRepository, coverage: CoverageRepository) =
        ClosedCandlePersister(candles, coverage, Executors.newSingleThreadExecutor(Thread.ofVirtual().name("persist-closed").factory()))

    @Bean
    fun liveCandleBook(clock: Clock, props: LiveProperties, persister: ClosedCandlePersister) = LiveCandleBook(clock, props.closeGrace, persister)

    @Bean
    fun tickerCache() = TickerCache()

    @Bean
    fun streamStatusTracker() = StreamStatusTracker()

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
            TradeHandler(book), SourceCandleHandler(book), TickerHandler(book, tickers), StreamStatusHandler(tracker),
            InstrumentHandler(instruments, clock), ViewingHandler(viewing),
        )
        return EventInbox(handlers, ContractJson.mapper())
    }

    @Bean
    fun startup(
        props: SourcesProperties, source: SourceClient, instruments: InstrumentRepository, tickers: TickerCache, book: LiveCandleBook,
        tracker: StreamStatusTracker, sync: CandleSync, candles: CandleRepository, clock: Clock,
    ) = Startup(props.sources.keys.toList(), source, instruments, tickers, book, tracker, sync, candles, clock)

    @Bean
    @ConditionalOnProperty("anthill.jobs.enabled", havingValue = "true")
    fun startupRunner(startup: Startup) = ApplicationRunner { Thread.ofVirtual().name("startup").start { startup.run() } }
}

@Component
@ConditionalOnProperty("anthill.jobs.enabled", havingValue = "true")
class LiveTickSchedule(private val publisher: LivePublisher) {
    @Scheduled(fixedRate = 1000)
    fun tick() = publisher.tick()
}
