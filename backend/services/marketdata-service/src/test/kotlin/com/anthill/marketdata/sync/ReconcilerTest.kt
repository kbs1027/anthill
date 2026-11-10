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

    private val meter = SimpleMeterRegistry()
    private fun reconciler() = Reconciler(ReconcilePlanner(), sync, coverage, instruments, TickerCache(), ViewingTracker(clock), outages, meter, clock)
    private fun late(u: CandleUnit) = meter.find("anthill.reconcile.late").tag("unit", u.code).counter()?.count() ?: 0.0

    private fun seed() = instruments.sync("UPBIT", listOf(InstrumentInfo(BTC, "비트코인", "Bitcoin")), now)

    @Test
    fun `mismatch corrected logged and pushed`() {
        seed()
        coverage.add(BTC, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:00:00Z"))
        candles.upsert(listOf(FakeSource.candle(t("2026-11-08T10:05:00Z"), close = "999")))
        source.put(listOf(FakeSource.candle(t("2026-11-08T10:05:00Z"), close = "101")))
        reconciler().runDue("UPBIT")
        assertTrue(candles.findRange(BTC, CandleUnit.M1, t("2026-11-08T10:05:00Z"), t("2026-11-08T10:06:00Z")).single().close.compareTo(java.math.BigDecimal("101")) == 0)
        assertEquals(1L, jdbc.sql("select count(*) from sync_correction where kind = 'update'").query(Long::class.java).single())
        assertEquals(t("2026-11-08T10:19:00Z"), coverage.end(BTC, CandleUnit.M1))  // 10:19 분봉은 정착 전(리뷰 I4)
    }

    @Test
    fun `quiet market window with no candles extends coverage`() {
        seed()
        coverage.add(BTC, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:00:00Z"))
        reconciler().runDue("UPBIT")
        assertEquals(t("2026-11-08T10:19:00Z"), coverage.end(BTC, CandleUnit.M1))  // 10:19 분봉은 정착 전(리뷰 I4)
    }

    @Test
    fun `paused reconciler makes no calls`() {
        seed()
        coverage.add(BTC, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:00:00Z"))
        val r = reconciler()
        r.pause("UPBIT")
        r.runDue("UPBIT")
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
        val overdue = reconciler().overdue("UPBIT", now)
        assertEquals(1, overdue[CandleUnit.M1])
        assertEquals(listOf(t("2026-11-08T10:00:00Z") to t("2026-11-08T10:09:00Z")), outages.windows(Instant.EPOCH, now))
    }

    @Test
    fun `active market without coverage gets verified`() {
        // 리뷰 I6: 운영 중 새로 상장된 종목은 누가 차트를 열기 전까지 검사 대상에 들어오지 않았다.
        seed()
        source.put(listOf(FakeSource.candle(t("2026-11-08T10:05:00Z"))))
        reconciler().runDue("UPBIT")
        for (u in CandleUnit.entries) assertTrue(coverage.end(BTC, u) != null, "no coverage for $u")
    }

    @Test
    fun `late verification is counted unless the close was in an outage`() {
        // 리뷰 I8: 지표가 순간값이라 24시간 동안 시한을 넘긴 적이 있었는지 알 수 없었다.
        seed()
        coverage.add(BTC, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:00:00Z"))   // 첫 미검증 마감 10:01, 시한 10:06, 지금 10:20
        reconciler().runDue("UPBIT")
        assertEquals(1.0, late(CandleUnit.M1))
    }

    @Test
    fun `verification of candles closed inside an outage is not counted late`() {
        seed()
        outages.open("UPBIT", t("2026-11-08T09:59:00Z"))
        outages.close("UPBIT", t("2026-11-08T10:10:00Z"))
        coverage.add(BTC, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:00:00Z"))
        reconciler().runDue("UPBIT")
        assertEquals(0.0, late(CandleUnit.M1))
    }

    // ---- 소스별 분리 ----

    private val bn = FakeSource.BN

    private fun seedBoth() {
        seed()
        instruments.sync("BINANCE", listOf(InstrumentInfo(bn, "BTC", "BTC")), now)
        for (i in listOf(BTC, bn)) coverage.add(i, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:00:00Z"))
    }

    private fun callsFor(i: com.anthill.contract.InstrumentId) = CandleUnit.entries.sumOf { source.callCount(i, it) }

    @Test
    fun `runDue of a source touches only that source`() {
        seedBoth()
        reconciler().runDue("BINANCE")
        assertEquals(0, callsFor(BTC))
        assertTrue(callsFor(bn) > 0)
        assertEquals(t("2026-11-08T10:19:00Z"), coverage.end(bn, CandleUnit.M1))
        assertEquals(t("2026-11-08T10:00:00Z"), coverage.end(BTC, CandleUnit.M1))
    }

    @Test
    fun `pause stops only that source`() {
        seedBoth()
        val r = reconciler()
        r.pause("UPBIT")
        r.runDue("UPBIT")
        assertEquals(0, source.totalCalls())
        r.runDue("BINANCE")
        assertTrue(callsFor(bn) > 0)
        r.resume("UPBIT")
        r.runDue("UPBIT")
        assertTrue(callsFor(BTC) > 0)
    }

    @Test
    fun `a source runs as many jobs at once as its parallelism`() {
        val list = (1..6).map { InstrumentInfo(com.anthill.contract.InstrumentId.parse("BINANCE:C${it}USDT"), "C$it", "C$it") }
        instruments.sync("BINANCE", list, now)
        list.forEach { coverage.add(it.id, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:00:00Z")) }
        val gate = java.util.concurrent.CountDownLatch(1)
        source.gate = gate
        val r = Reconciler(ReconcilePlanner(), sync, coverage, instruments, TickerCache(), ViewingTracker(clock), outages, meter, clock, mapOf("BINANCE" to 4))
        val worker = Thread.ofVirtual().start { r.runDue("BINANCE") }
        val until = System.nanoTime() + 5_000_000_000L
        while (source.totalCalls() < 4 && System.nanoTime() < until) Thread.sleep(5)
        Thread.sleep(100)
        assertEquals(4, source.totalCalls())   // 4개가 동시에 소스 응답을 기다린다(그 이상은 아님)
        gate.countDown()
        worker.join(10_000)
        list.forEach { assertEquals(t("2026-11-08T10:19:00Z"), coverage.end(it.id, CandleUnit.M1)) }
    }

    @Test
    fun `metrics carry the source tag`() {
        seedBoth()
        coverage.add(BTC, CandleUnit.M5, Instant.EPOCH, t("2026-11-08T09:00:00Z"))
        val r = reconciler()
        r.runDue("UPBIT")
        assertEquals(1.0, meter.find("anthill.reconcile.late").tag("source", "UPBIT").tag("unit", "1m").counter()?.count())
        assertEquals(null, meter.find("anthill.reconcile.late").tag("source", "BINANCE").counter())
        assertTrue(meter.find("anthill.reconcile.lag.seconds").tag("source", "UPBIT").tag("unit", "1m").gauge() != null)
    }

    @Test
    fun `outage of another source does not excuse lateness`() {
        seed()
        outages.open("BINANCE", t("2026-11-08T09:59:00Z"))
        outages.close("BINANCE", t("2026-11-08T10:10:00Z"))
        coverage.add(BTC, CandleUnit.M1, Instant.EPOCH, t("2026-11-08T10:00:00Z"))
        val r = reconciler()
        assertEquals(1, r.overdue("UPBIT", now)[CandleUnit.M1])
        r.runDue("UPBIT")
        assertEquals(1.0, late(CandleUnit.M1))
    }
}
