package com.anthill.contract

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CandleMergeTest {

    private val zil = InstrumentId.parse("BINANCE:ZILUSDT")
    private val minute = Instant.parse("2026-11-10T12:00:00Z")

    private fun piece(o: String, h: String, l: String, c: String, v: String, a: String) =
        Candle(zil, CandleUnit.M1, minute, BigDecimal(o), BigDecimal(h), BigDecimal(l), BigDecimal(c), BigDecimal(v), BigDecimal(a))

    private fun quiet(price: String) = piece(price, price, price, price, "0", "0")

    @Test
    fun `first traded piece replaces open high low close of a quiet bucket`() {
        val opened = quiet("0.00162100")
        val merged = CandleMerge.merge(opened, piece("0.00162300", "0.00162400", "0.00162200", "0.00162200", "1000", "1.623"))
        assertEquals(BigDecimal("0.00162300"), merged.open)
        assertEquals(BigDecimal("0.00162400"), merged.high)
        assertEquals(BigDecimal("0.00162200"), merged.low)
        assertEquals(BigDecimal("0.00162200"), merged.close)
        assertEquals(BigDecimal("1000"), merged.volume)
        assertEquals(minute, merged.start)
    }

    @Test
    fun `quiet piece leaves the bucket untouched`() {
        val acc = piece("1.0", "1.2", "0.9", "1.1", "5", "5.5")
        assertSame(acc, CandleMerge.merge(acc, quiet("1.1")))
    }

    @Test
    fun `traded pieces widen high low, move close and add volume and amount exactly`() {
        val acc = piece("1.0", "1.2", "0.9", "1.1", "0.1", "0.11")
        val merged = CandleMerge.merge(acc, piece("1.1", "1.3", "0.8", "1.05", "0.2", "0.22"))
        assertEquals(BigDecimal("1.0"), merged.open)
        assertEquals(BigDecimal("1.3"), merged.high)
        assertEquals(BigDecimal("0.8"), merged.low)
        assertEquals(BigDecimal("1.05"), merged.close)
        assertEquals(0, BigDecimal("0.3").compareTo(merged.volume))
        assertEquals(0, BigDecimal("0.33").compareTo(merged.amount))
    }

    @Test
    fun `a bucket with no trades at all stays at the previous close with zero volume`() {
        val merged = listOf(quiet("2.5"), quiet("2.5")).fold(quiet("2.5")) { acc, p -> CandleMerge.merge(acc, p) }
        assertEquals(BigDecimal("2.5"), merged.open)
        assertEquals(BigDecimal("2.5"), merged.close)
        assertEquals(0, merged.volume.signum())
    }

    @Test
    fun `second candle becomes a piece of the requested bucket`() {
        val s = SecondCandle(zil, Instant.parse("2026-11-10T12:03:17Z"), BigDecimal("1"), BigDecimal("2"), BigDecimal("0.5"), BigDecimal("1.5"), BigDecimal("3"), BigDecimal("4.5"))
        val c = s.toCandle(CandleUnit.M5, Instant.parse("2026-11-10T12:00:00Z"))
        assertEquals(CandleUnit.M5, c.unit)
        assertEquals(Instant.parse("2026-11-10T12:00:00Z"), c.start)
        assertEquals(BigDecimal("4.5"), c.amount)
    }
}
