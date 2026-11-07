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
}
