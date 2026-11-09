package com.anthill.marketdata.live

import com.anthill.contract.CandleUnit
import com.anthill.marketdata.FakeSource
import com.anthill.marketdata.FakeSource.Companion.BTC
import com.anthill.marketdata.PgTest
import com.anthill.marketdata.candle.CandleRepository
import com.anthill.marketdata.candle.CandleChangeSink
import com.anthill.marketdata.candle.CoverageRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClosedCandlePersisterTest : PgTest() {

    @Autowired lateinit var candles: CandleRepository
    @Autowired lateinit var coverage: CoverageRepository

    private val t0 = Instant.parse("2026-11-08T10:00:00Z")
    private val pushed = mutableListOf<java.time.Instant>()
    private fun persister() = ClosedCandlePersister(candles, coverage, { it.run() }, CandleChangeSink { _, _, up, _ -> pushed += up.map { it.start } })

    @Test
    fun `closed candle in verified range is not persisted`() {
        val verified = FakeSource.candle(t0, close = "100")
        candles.upsert(listOf(verified))
        coverage.add(BTC, CandleUnit.M1, t0, t0.plusSeconds(60))
        persister().onClosed(verified.copy(close = verified.close.add(java.math.BigDecimal.ONE)), false)
        assertTrue(candles.findRange(BTC, CandleUnit.M1, t0, t0.plusSeconds(60)).single().sameValuesAs(verified))
    }

    @Test
    fun `closed candle outside coverage is upserted`() {
        val c = FakeSource.candle(t0)
        persister().onClosed(c, false)
        assertEquals(emptyList(), pushed)
        assertEquals(listOf(c.start), candles.findRange(BTC, CandleUnit.M1, t0, t0.plusSeconds(60)).map { it.start })
    }

    @Test
    fun `late value outside coverage is saved and pushed as a correction`() {
        val c = FakeSource.candle(t0, close = "101")
        persister().onClosed(c, true)
        assertTrue(candles.findRange(BTC, CandleUnit.M1, t0, t0.plusSeconds(60)).single().sameValuesAs(c))
        assertEquals(listOf(t0), pushed)
    }

    @Test
    fun `late value in verified range is neither saved nor pushed`() {
        val verified = FakeSource.candle(t0, close = "100")
        candles.upsert(listOf(verified))
        coverage.add(BTC, CandleUnit.M1, t0, t0.plusSeconds(60))
        persister().onClosed(verified.copy(close = java.math.BigDecimal("999")), true)
        assertTrue(candles.findRange(BTC, CandleUnit.M1, t0, t0.plusSeconds(60)).single().sameValuesAs(verified))
        assertEquals(emptyList(), pushed)
    }
}
