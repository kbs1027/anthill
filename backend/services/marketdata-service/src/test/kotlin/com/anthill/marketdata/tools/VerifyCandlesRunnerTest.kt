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

    @Test
    fun `reports pairs without coverage and pairs past the deadline`() {
        // 리뷰 I6·I8: 한 번도 확인하지 않은 (종목, 단위)와 확인이 시한을 넘긴 것도 보고한다.
        coverage.add(BTC, CandleUnit.M5, Instant.EPOCH, from)            // 마지막 확인 10:00, 지금 12:00 → 시한 지남
        val report = verifier().verify(listOf(BTC to CandleUnit.M1, BTC to CandleUnit.M5), now)
        assertEquals(listOf("UPBIT:KRW-BTC 1m"), report.uncovered)
        assertEquals(listOf("UPBIT:KRW-BTC 5m"), report.overdue.map { it.substringBefore(" since") })
    }

    @Test
    fun `samples are picked per source`() {
        fun tickers(source: String, scale: Long) = (1..20).map { n ->
            val id = com.anthill.contract.InstrumentId.parse("$source:S$n")
            val day = FakeSource.candle(now, instrument = id, unit = CandleUnit.DAY)
            com.anthill.contract.TickerSnapshot(id, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(n * scale), day)
        }
        // 원화 금액이 숫자상 훨씬 크다. 한 줄로 세우면 바이낸스 상위 종목이 뽑히지 않는다.
        val ids = VerifyCandlesRunner.samples(mapOf("UPBIT" to tickers("UPBIT", 1_000_000_000), "BINANCE" to tickers("BINANCE", 1)), kotlin.random.Random(1))
        for (source in listOf("UPBIT", "BINANCE")) {
            val mine = ids.filter { it.source == source }.map { it.symbol }.toSet()
            assertTrue(mine.containsAll((16..20).map { "S$it" }), "$source top: $mine")
            assertTrue(mine.containsAll((1..5).map { "S$it" }), "$source bottom: $mine")
            assertTrue(mine.size in 10..15, "$source size: $mine")
        }
    }

    @Test
    fun `only the instrument's own source outage excuses the deadline`() {
        coverage.add(BTC, CandleUnit.M5, Instant.EPOCH, from)            // 첫 미검증 마감 10:05
        outages.open("BINANCE", from)
        outages.close("BINANCE", from.plusSeconds(3600))
        assertEquals(1, verifier().verify(listOf(BTC to CandleUnit.M5), now).overdue.size)
        outages.open("UPBIT", from)
        outages.close("UPBIT", from.plusSeconds(3600))
        assertEquals(0, verifier().verify(listOf(BTC to CandleUnit.M5), now).overdue.size)
    }
}
