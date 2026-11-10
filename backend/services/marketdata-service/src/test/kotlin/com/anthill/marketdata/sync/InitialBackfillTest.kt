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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitialBackfillTest : PgTest() {

    @Autowired lateinit var sync: CandleSync
    @Autowired lateinit var coverage: CoverageRepository
    @Autowired lateinit var candles: CandleRepository
    @Autowired lateinit var instruments: InstrumentRepository

    private val now = Instant.parse("2026-11-08T10:20:30Z")
    private val backfill by lazy {
        InitialBackfill(instruments, coverage, sync, TickerCache(), Clock.fixed(now, ZoneOffset.UTC), minuteDays = 7)
    }

    private fun seed() = instruments.sync("UPBIT", listOf(InstrumentInfo(BTC, "비트코인", "Bitcoin")), now)

    @Test
    fun `daily history stops at listing date`() {
        seed()
        // 마감된 일봉 450개(상장일 ~ 어제). 오늘 일봉은 진행 중이라 백필 대상이 아니다.
        val listing = CandleUnit.DAY.floor(now).minus(Duration.ofDays(450))
        source.put((0 until 450).map { FakeSource.candle(listing.plus(Duration.ofDays(it.toLong())), unit = CandleUnit.DAY) })
        backfill.runOnce("UPBIT")
        assertEquals(Instant.EPOCH, coverage.start(BTC, CandleUnit.DAY))
        assertEquals(450, candles.findRange(BTC, CandleUnit.DAY, Instant.EPOCH, now).size)
        assertEquals(3, source.callCount(BTC, CandleUnit.DAY))
    }

    @Test
    fun `minute backfill covers exactly seven days`() {
        seed()
        val tenDaysAgo = CandleUnit.M1.floor(now.minus(Duration.ofDays(10)))
        source.put(FakeSource.series(tenDaysAgo, 480, stepMinutes = 30))
        backfill.runOnce("UPBIT")
        val sevenDaysAgo = CandleUnit.M1.floor(now.minus(Duration.ofDays(7)))
        assertEquals(sevenDaysAgo, coverage.start(BTC, CandleUnit.M1))
        val stored = candles.findRange(BTC, CandleUnit.M1, Instant.EPOCH, now)
        assertEquals(true, stored.all { it.start >= sevenDaysAgo })
        assertEquals(336, stored.size)
    }

    @Test
    fun `rerun makes no new calls`() {
        seed()
        source.put(FakeSource.series(CandleUnit.M1.floor(now.minus(Duration.ofDays(2))), 50, stepMinutes = 30))
        backfill.runOnce("UPBIT")
        val calls = source.totalCalls()
        backfill.runOnce("UPBIT")
        assertEquals(calls, source.totalCalls())
    }

    @Test
    fun `backfill runs separately per source`() {
        val bn = FakeSource.BN
        seed()
        instruments.sync("BINANCE", listOf(InstrumentInfo(bn, "BTC", "BTC")), now)
        backfill.runOnce("UPBIT")
        assertTrue(source.callCount(BTC, CandleUnit.DAY) > 0)
        assertEquals(0, CandleUnit.entries.sumOf { source.callCount(bn, it) })

        // 두 소스가 동시에 돈다: 한쪽이 소스 응답을 기다려도 다른 쪽이 시작한다.
        source.reset()
        jdbc.sql("truncate coverage").update()
        val gate = java.util.concurrent.CountDownLatch(1)
        source.gate = gate
        backfill.startAsync("UPBIT")
        backfill.startAsync("BINANCE")
        val until = System.nanoTime() + 5_000_000_000L
        while ((source.callCount(BTC, CandleUnit.DAY) == 0 || source.callCount(bn, CandleUnit.DAY) == 0) && System.nanoTime() < until) Thread.sleep(5)
        assertEquals(1, source.callCount(BTC, CandleUnit.DAY))
        assertEquals(1, source.callCount(bn, CandleUnit.DAY))
        gate.countDown()
        // 다음 테스트가 테이블을 비우기 전에 둘 다 끝나게 기다린다(마지막 단위가 4시간봉).
        while ((source.callCount(BTC, CandleUnit.M240) == 0 || source.callCount(bn, CandleUnit.M240) == 0) && System.nanoTime() < until) Thread.sleep(5)
        Thread.sleep(200)
    }
}
