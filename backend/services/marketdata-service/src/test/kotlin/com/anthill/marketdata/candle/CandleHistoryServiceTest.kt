package com.anthill.marketdata.candle

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.marketdata.FakeSource
import com.anthill.marketdata.PgTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandleHistoryServiceTest : PgTest() {

    @Autowired lateinit var candles: CandleRepository
    @Autowired lateinit var coverage: CoverageRepository
    @Autowired lateinit var sync: CandleSync

    private val btc = FakeSource.BTC
    private val now = Instant.parse("2026-11-08T00:00:30Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private class FixedLive(var since: Instant?) : LiveView {
        override fun current(i: InstrumentId, u: CandleUnit): Candle? = null
        override fun streamConnectedSince(source: String) = since
    }

    private fun service(live: LiveView = FixedLive(null)) = CandleHistoryService(candles, coverage, sync, live, clock)

    /** 3,494분에 흩어진 200개처럼, 약 17분 간격의 드문 1분봉. */
    private fun sparse(n: Int) = FakeSource.series(now.minusSeconds(17L * 60 * n).let { CandleUnit.M1.floor(it) }, n, stepMinutes = 17)

    @Test
    fun `sparse market pages back without refetching covered range`() {
        source.put(sparse(450))
        val page1 = service().page(btc, CandleUnit.M1, null, 200)
        assertEquals(200, page1.candles.size)
        assertEquals(1, source.callCount(btc, CandleUnit.M1))
        val to = page1.candles.first().start
        val page2 = service().page(btc, CandleUnit.M1, to, 200)
        assertEquals(200, page2.candles.size)
        val callsAfterPage2 = source.callCount(btc, CandleUnit.M1)
        service().page(btc, CandleUnit.M1, to, 200)
        service().page(btc, CandleUnit.M1, null, 200)
        assertEquals(callsAfterPage2, source.callCount(btc, CandleUnit.M1))
        assertTrue(page2.candles.last().start < to)
    }

    @Test
    fun `gaps are not filled`() {
        val src = sparse(30)
        source.put(src)
        val page = service().page(btc, CandleUnit.M1, null, 200)
        assertEquals(src.map { it.start }, page.candles.map { it.start })
    }

    @Test
    fun `stream window is served from db when stream covers it`() {
        val covEnd = CandleUnit.M1.floor(now).minusSeconds(600)
        coverage.add(btc, CandleUnit.M1, Instant.EPOCH, covEnd)
        candles.upsert(FakeSource.series(covEnd.minusSeconds(3000), 60))
        val page = service(FixedLive(covEnd.minusSeconds(1))).page(btc, CandleUnit.M1, null, 20)
        assertEquals(0, source.totalCalls())
        assertEquals(20, page.candles.size)
        assertEquals(CandleUnit.M1.floor(now).minusSeconds(60), page.candles.last().start)
    }

    @Test
    fun `stream gap forces fetch`() {
        val covEnd = CandleUnit.M1.floor(now).minusSeconds(600)
        coverage.add(btc, CandleUnit.M1, Instant.EPOCH, covEnd)
        source.put(FakeSource.series(covEnd.minusSeconds(3000), 60))
        service(FixedLive(covEnd.plusSeconds(120))).page(btc, CandleUnit.M1, null, 20)
        assertEquals(1, source.callCount(btc, CandleUnit.M1))
    }

    @Test
    fun `hasMore false after history start`() {
        source.put(FakeSource.series(CandleUnit.M1.floor(now).minusSeconds(50 * 60), 50))
        val page = service().page(btc, CandleUnit.M1, null, 200)
        assertEquals(50, page.candles.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun `latest page shows the just closed candle without marking it verified`() {
        // 리뷰 I4: 10:05:10에 10:04 분봉은 마감 10초째라 확인 구간에 넣지 않지만, 화면에는 보여야 한다.
        val at = Instant.parse("2026-11-08T10:05:10Z")
        val series = FakeSource.series(Instant.parse("2026-11-08T10:00:00Z"), 5)
        source.put(series)
        val page = CandleHistoryService(candles, coverage, sync, FixedLive(null), Clock.fixed(at, ZoneOffset.UTC)).page(btc, CandleUnit.M1, null, 200)
        assertEquals(series.map { it.start }, page.candles.map { it.start })
        assertEquals(Instant.parse("2026-11-08T10:04:00Z"), coverage.end(btc, CandleUnit.M1))
    }
}
