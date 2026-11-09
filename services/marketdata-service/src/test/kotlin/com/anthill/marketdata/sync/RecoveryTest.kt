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
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryTest : PgTest() {

    @Autowired lateinit var sync: CandleSync
    @Autowired lateinit var coverage: CoverageRepository
    @Autowired lateinit var candles: CandleRepository
    @Autowired lateinit var instruments: InstrumentRepository
    @Autowired lateinit var outages: OutageLog

    private val now = Instant.parse("2026-11-08T10:20:30Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val gapStart = Instant.parse("2026-11-08T10:15:00Z")

    private fun parts(retryDelay: java.time.Duration = java.time.Duration.ofMillis(10)): Pair<Reconciler, Recovery> {
        val reconciler = Reconciler(ReconcilePlanner(), sync, coverage, instruments, TickerCache(), ViewingTracker(clock), outages, SimpleMeterRegistry(), clock)
        val backfill = InitialBackfill(instruments, coverage, sync, TickerCache(), clock, minuteDays = 0)
        return reconciler to Recovery(instruments, coverage, sync, reconciler, backfill, outages, clock, retryDelay)
    }

    private fun seed() {
        instruments.sync("UPBIT", listOf(InstrumentInfo(BTC, "비트코인", "Bitcoin")), now)
        CandleUnit.entries.forEach { coverage.add(BTC, it, Instant.EPOCH, it.floor(gapStart)) }
    }

    @Test
    fun `five minute gap is filled exactly`() {
        seed()
        source.put(FakeSource.series(gapStart, 5))
        outages.open("UPBIT", gapStart)
        parts().second.recoverAll("UPBIT")
        val db = candles.findRange(BTC, CandleUnit.M1, gapStart, CandleUnit.M1.floor(now))
        assertEquals(5, db.size)
        assertEquals(CandleUnit.M1.floor(now), coverage.end(BTC, CandleUnit.M1))
        assertTrue(outages.windows(Instant.EPOCH, now).single().second <= now)
    }

    @Test
    fun `reconciler is paused during recovery and resumed after`() {
        seed()
        val (reconciler, recovery) = parts()
        val gate = CountDownLatch(1)
        source.gate = gate
        val worker = Thread.ofVirtual().start { recovery.recoverAll("UPBIT") }
        while (source.totalCalls() == 0) Thread.sleep(5)
        assertTrue(reconciler.paused)
        gate.countDown()
        worker.join(5000)
        assertFalse(reconciler.paused)
    }

    @Test
    fun `adapter reconnect triggers recovery once`() {
        seed()
        val (_, recovery) = parts()
        val gate = CountDownLatch(1)
        source.gate = gate
        val first = Thread.ofVirtual().start { recovery.recoverAll("UPBIT") }
        while (source.totalCalls() == 0) Thread.sleep(5)
        recovery.recoverAll("UPBIT")       // 이미 도는 중이면 바로 돌아온다
        gate.countDown()
        first.join(5000)
        val unitsNeedingSync = CandleUnit.entries.count { it.floor(gapStart) < it.floor(now) }
        assertEquals(unitsNeedingSync, source.totalCalls())
    }

    @Test
    fun `failed syncs are retried before the outage closes`() {
        // Task 12 실측: 어댑터 REST가 열리기 전에 복구가 돌아 1,800건이 모두 실패했는데 장애 구간을 닫았다(리뷰 M6).
        seed()
        source.put(FakeSource.series(gapStart, 5))
        outages.open("UPBIT", gapStart)
        source.candleFailures.set(3)
        parts().second.recoverAll("UPBIT")
        assertEquals(0L, jdbc.sql("select count(*) from outage where to_at is null").query(Long::class.java).single())
        assertEquals(CandleUnit.M1.floor(now), coverage.end(BTC, CandleUnit.M1))
        assertEquals(5, candles.findRange(BTC, CandleUnit.M1, gapStart, CandleUnit.M1.floor(now)).size)
    }

    @Test
    fun `a second recovery right after the first makes no calls`() {
        seed()
        source.put(FakeSource.series(gapStart, 5))
        val recovery = parts().second
        recovery.recoverAll("UPBIT")
        val calls = source.totalCalls()
        recovery.recoverAll("UPBIT")
        assertEquals(calls, source.totalCalls())
    }
}
