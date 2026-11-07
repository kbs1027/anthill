package com.anthill.marketdata.live

import com.anthill.contract.CandleUnit
import com.anthill.marketdata.FakeSource
import com.anthill.marketdata.FakeSource.Companion.BTC
import com.anthill.marketdata.PgTest
import com.anthill.marketdata.candle.CandleRepository
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

    @Test
    fun `closed candle in verified range is not persisted`() {
        val verified = FakeSource.candle(t0, close = "100")
        candles.upsert(listOf(verified))
        coverage.add(BTC, CandleUnit.M1, t0, t0.plusSeconds(60))
        ClosedCandlePersister(candles, coverage) { it.run() }.onClosed(verified.copy(close = verified.close.add(java.math.BigDecimal.ONE)))
        assertTrue(candles.findRange(BTC, CandleUnit.M1, t0, t0.plusSeconds(60)).single().sameValuesAs(verified))
    }

    @Test
    fun `closed candle outside coverage is upserted`() {
        val c = FakeSource.candle(t0)
        ClosedCandlePersister(candles, coverage) { it.run() }.onClosed(c)
        assertEquals(listOf(c.start), candles.findRange(BTC, CandleUnit.M1, t0, t0.plusSeconds(60)).map { it.start })
    }
}
