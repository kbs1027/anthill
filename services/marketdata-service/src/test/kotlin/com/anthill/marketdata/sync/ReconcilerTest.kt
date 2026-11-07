package com.anthill.marketdata.sync

import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentInfo
import com.anthill.marketdata.FakeSource
import com.anthill.marketdata.FakeSource.Companion.BTC
import com.anthill.marketdata.PgTest
import com.anthill.marketdata.candle.CandleRepository
import com.anthill.marketdata.candle.CandleSync
import com.anthill.marketdata.candle.CoverageRepository
import com.anthill.marketdata.candle.InstrumentRepository
import com.anthill.marketdata.live.TickerCache
import com.anthill.marketdata.live.ViewingTracker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcilerTest : PgTest() {

    @Autowired lateinit var sync: CandleSync
    @Autowired lateinit var coverage: CoverageRepository
    @Autowired lateinit var candles: CandleRepository
    @Autowired lateinit var instruments: InstrumentRepository
    @Autowired lateinit var outages: OutageLog

    private val now = Instant.parse("2026-11-08T10:20:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private fun t(s: String) = Instant.parse(s)

    private fun reconciler() = Reconciler(ReconcilePlanner(), sync, coverage, instruments, TickerCache(), ViewingTracker(clock), outages, SimpleMeterRegistry(), clock)

    private fun seed() = instruments.sync("UPBIT", listOf(InstrumentInfo(BTC, "비트코인", "Bitcoin")), now)

    @Test
    fun `mismatch corrected logged and pushed`() {
        seed()
        coverage.add(BTC, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:00:00Z"))
        candles.upsert(listOf(FakeSource.candle(t("2026-11-08T10:05:00Z"), close = "999")))
        source.put(listOf(FakeSource.candle(t("2026-11-08T10:05:00Z"), close = "101")))
        reconciler().runDue()
        assertTrue(candles.findRange(BTC, CandleUnit.M1, t("2026-11-08T10:05:00Z"), t("2026-11-08T10:06:00Z")).single().close.compareTo(java.math.BigDecimal("101")) == 0)
        assertEquals(1L, jdbc.sql("select count(*) from sync_correction where kind = 'update'").query(Long::class.java).single())
        assertEquals(t("2026-11-08T10:20:00Z"), coverage.end(BTC, CandleUnit.M1))
    }

    @Test
    fun `quiet market window with no candles extends coverage`() {
        seed()
        coverage.add(BTC, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:00:00Z"))
        reconciler().runDue()
        assertEquals(t("2026-11-08T10:20:00Z"), coverage.end(BTC, CandleUnit.M1))
    }

    @Test
    fun `paused reconciler makes no calls`() {
        seed()
        coverage.add(BTC, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:00:00Z"))
        val r = reconciler()
        r.pause()
        r.runDue()
        assertEquals(0, source.totalCalls())
    }

    @Test
    fun `outage window is recorded and excluded from overdue`() {
        seed()
        val other = com.anthill.contract.InstrumentId.parse("UPBIT:KRW-XRP")
        instruments.sync("UPBIT", listOf(InstrumentInfo(BTC, "비트코인", "Bitcoin"), InstrumentInfo(other, "리플", "XRP")), now)
        outages.open("UPBIT", t("2026-11-08T10:00:00Z"))
        outages.close("UPBIT", t("2026-11-08T10:09:00Z"))
        coverage.add(BTC, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:02:00Z"))   // 첫 미검증 마감 10:03 → 장애 구간 안
        coverage.add(other, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:12:00Z")) // 첫 미검증 마감 10:13 → 장애 구간 밖
        val overdue = reconciler().overdue(now)
        assertEquals(1, overdue[CandleUnit.M1])
        assertEquals(listOf(t("2026-11-08T10:00:00Z") to t("2026-11-08T10:09:00Z")), outages.windows(Instant.EPOCH, now))
    }
}
