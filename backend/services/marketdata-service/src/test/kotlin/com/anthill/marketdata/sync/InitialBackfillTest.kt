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
        backfill.runOnce()
        assertEquals(Instant.EPOCH, coverage.start(BTC, CandleUnit.DAY))
        assertEquals(450, candles.findRange(BTC, CandleUnit.DAY, Instant.EPOCH, now).size)
        assertEquals(3, source.callCount(BTC, CandleUnit.DAY))
    }

    @Test
    fun `minute backfill covers exactly seven days`() {
        seed()
        val tenDaysAgo = CandleUnit.M1.floor(now.minus(Duration.ofDays(10)))
        source.put(FakeSource.series(tenDaysAgo, 480, stepMinutes = 30))
        backfill.runOnce()
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
        backfill.runOnce()
        val calls = source.totalCalls()
        backfill.runOnce()
        assertEquals(calls, source.totalCalls())
    }
}
