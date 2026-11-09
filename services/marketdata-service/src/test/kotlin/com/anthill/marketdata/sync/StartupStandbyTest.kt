package com.anthill.marketdata.sync

import com.anthill.contract.InstrumentInfo
import com.anthill.contract.StreamStatus
import com.anthill.marketdata.FakeSource.Companion.BTC
import com.anthill.marketdata.PgTest
import com.anthill.marketdata.candle.CandleRepository
import com.anthill.marketdata.candle.CandleSync
import com.anthill.marketdata.candle.CoverageRepository
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

class StartupStandbyTest : PgTest() {
    @Autowired lateinit var instruments: InstrumentRepository
    @Autowired lateinit var sync: CandleSync
    @Autowired lateinit var candles: CandleRepository
    @Autowired lateinit var coverage: CoverageRepository
    @Autowired lateinit var outages: OutageLog

    @Test
    fun `a standby coming up does not record an outage`() {
        coverage.add(BTC, com.anthill.contract.CandleUnit.M1, Instant.EPOCH, Instant.parse("2026-11-08T09:55:00Z"))
        source.instruments = listOf(InstrumentInfo(BTC, "비트코인", "Bitcoin"))
        source.status = StreamStatus(true, Instant.parse("2026-11-08T09:00:00Z"))
        val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
        Startup(
            listOf("UPBIT"), source, instruments, TickerCache(), LiveCandleBook(clock, Duration.ofSeconds(10)) { _, _ -> }, StreamStatusTracker(),
            sync, candles, clock, outages, coverage, retry = Duration.ofMillis(10), isLeader = { false },
        ).run()
        assertEquals(emptyList(), outages.windows(Instant.EPOCH, clock.instant()))
    }
}
