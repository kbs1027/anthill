package com.anthill.marketdata.tools

import com.anthill.contract.CandleUnit
import com.anthill.marketdata.FakeSource
import com.anthill.marketdata.FakeSource.Companion.BTC
import com.anthill.marketdata.PgTest
import com.anthill.marketdata.candle.CandleRepository
import com.anthill.marketdata.candle.CoverageRepository
import com.anthill.marketdata.sync.OutageLog
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifyCandlesRunnerTest : PgTest() {

    @Autowired lateinit var candles: CandleRepository
    @Autowired lateinit var coverage: CoverageRepository
    @Autowired lateinit var outages: OutageLog

    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val from = Instant.parse("2026-11-08T10:00:00Z")

    private fun verifier() = CandleVerifier(source, candles, coverage, outages)

    private fun seed(): List<com.anthill.contract.Candle> {
        val series = FakeSource.series(from, 30)
        source.put(series)
        candles.upsert(series)
        coverage.add(BTC, CandleUnit.M1, Instant.EPOCH, from.plusSeconds(60 * 60))
        return series
    }

    @Test
    fun `reports count mismatch`() {
        val series = seed()
        candles.delete(BTC, CandleUnit.M1, listOf(series[3].start))
        val report = verifier().verify(listOf(BTC to CandleUnit.M1), now)
        assertEquals(1, report.mismatches.size)
        assertTrue(report.mismatches.single().contains("UPBIT:KRW-BTC"), report.mismatches.single())
    }

    @Test
    fun `reports value mismatch`() {
        val series = seed()
        candles.upsert(listOf(series[5].copy(close = BigDecimal("12345"))))
        assertEquals(1, verifier().verify(listOf(BTC to CandleUnit.M1), now).mismatches.size)
    }

    @Test
    fun `scale-only difference passes`() {
        val series = seed()
        candles.upsert(listOf(series[5].copy(close = series[5].close.setScale(8))))
        val report = verifier().verify(listOf(BTC to CandleUnit.M1), now)
        assertTrue(report.mismatches.isEmpty())
        assertEquals(30, report.checkedCandles)
    }
}
