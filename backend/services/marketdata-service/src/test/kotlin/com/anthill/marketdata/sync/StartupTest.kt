package com.anthill.marketdata.sync

import com.anthill.contract.InstrumentInfo
import com.anthill.contract.StreamStatus
import com.anthill.marketdata.FakeSource.Companion.BTC
import com.anthill.marketdata.PgTest
import com.anthill.marketdata.candle.CandleRepository
import com.anthill.marketdata.candle.CandleSync
import com.anthill.marketdata.candle.InstrumentRepository
import com.anthill.marketdata.live.LiveCandleBook
import com.anthill.marketdata.live.StreamStatusTracker
import com.anthill.marketdata.live.TickerCache
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class StartupTest : PgTest() {

    @Autowired lateinit var instruments: InstrumentRepository
    @Autowired lateinit var sync: CandleSync
    @Autowired lateinit var candles: CandleRepository

    @Test
    fun `startup waits for source instead of failing`() {
        val since = Instant.parse("2026-11-08T09:00:00Z")
        source.instruments = listOf(InstrumentInfo(BTC, "비트코인", "Bitcoin"))
        source.status = StreamStatus(true, since)
        source.failures = 2
        val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
        val tracker = StreamStatusTracker()
        val startup = Startup(
            listOf("UPBIT"), source, instruments, TickerCache(), LiveCandleBook(clock, Duration.ofSeconds(10)) { _, _ -> }, tracker,
            sync, candles, clock, retry = Duration.ofMillis(10),
        )
        startup.run()
        assertEquals(listOf(BTC), instruments.active().map { it.id })
        assertEquals(since, tracker.connectedSince("UPBIT"))
    }

    @Autowired lateinit var coverage: com.anthill.marketdata.candle.CoverageRepository
    @Autowired lateinit var outages: OutageLog

    @Test
    fun `outage is opened before waiting for the source`() {
        // Task 12 실측: 어댑터를 기다리는 사이 어댑터 알림이 먼저 와 복구가 돌고 끝난 뒤에야 장애 구간이 열려, 영영 닫히지 않았다.
        coverage.add(BTC, com.anthill.contract.CandleUnit.M1, Instant.EPOCH, Instant.parse("2026-11-08T09:55:00Z"))
        source.instruments = listOf(InstrumentInfo(BTC, "비트코인", "Bitcoin"))
        source.status = StreamStatus(true, Instant.parse("2026-11-08T09:00:00Z"))
        source.failures = 1_000_000
        val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
        val startup = Startup(
            listOf("UPBIT"), source, instruments, TickerCache(), LiveCandleBook(clock, Duration.ofSeconds(10)) { _, _ -> }, StreamStatusTracker(),
            sync, candles, clock, outages, coverage, retry = Duration.ofMillis(10),
        )
        val t = Thread.ofVirtual().start { startup.run() }
        val until = System.nanoTime() + 2_000_000_000L
        while (outages.windows(Instant.EPOCH, clock.instant()).isEmpty() && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(listOf(Instant.parse("2026-11-08T09:55:00Z") to clock.instant()), outages.windows(Instant.EPOCH, clock.instant()))
        source.failures = 0
        t.join(5_000)
    }

    @Test
    fun `a source that is not up does not hold back the other`() {
        val bn = com.anthill.marketdata.FakeSource.BN
        source.instruments = listOf(InstrumentInfo(BTC, "비트코인", "Bitcoin"), InstrumentInfo(bn, "BTC", "BTC"))
        source.status = StreamStatus(true, Instant.parse("2026-11-08T09:00:00Z"))
        source.downSources = setOf("BINANCE")
        val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
        val startup = Startup(
            listOf("BINANCE", "UPBIT"), source, instruments, TickerCache(), LiveCandleBook(clock, Duration.ofSeconds(10)) { _, _ -> }, StreamStatusTracker(),
            sync, candles, clock, outages, coverage, retry = Duration.ofMillis(10),
        )
        val ready = java.util.concurrent.CopyOnWriteArrayList<String>()
        val t = Thread.ofVirtual().start { startup.run { ready += it } }
        val until = System.nanoTime() + 5_000_000_000L
        while ("UPBIT" !in ready && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(listOf("UPBIT"), ready.toList())
        assertEquals(listOf(BTC), instruments.active().map { it.id })
        source.downSources = emptySet()
        t.join(5_000)
        assertEquals(setOf("UPBIT", "BINANCE"), ready.toSet())
        assertEquals(setOf(BTC, bn), instruments.active().map { it.id }.toSet())
    }
}
