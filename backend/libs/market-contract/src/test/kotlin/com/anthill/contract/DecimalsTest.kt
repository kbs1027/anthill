package com.anthill.contract

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecimalsTest {

    @Test
    fun `same value different scale`() =
        assertTrue(Decimals.same(BigDecimal("41983565546.39229000"), BigDecimal("41983565546.3922900000000000")))

    @Test
    fun `format strips trailing zeros without exponent`() {
        assertEquals("108328000", Decimals.format(BigDecimal("108328000.00000000")))
        assertEquals("0.00012", Decimals.format(BigDecimal("0.000120")))
        assertEquals("0", Decimals.format(BigDecimal("0E-8")))
    }

    @Test
    fun `candle sameValuesAs ignores scale but not value`() {
        val a = candle("108328000", "41983565546.39229")
        val b = candle("108328000.00000000", "41983565546.3922900000000000")
        assertTrue(a.sameValuesAs(b))
        assertFalse(a.sameValuesAs(b.copy(close = BigDecimal("108328000.00000001"))))
    }

    private fun candle(close: String, amount: String) = Candle(
        InstrumentId.parse("UPBIT:KRW-BTC"), CandleUnit.DAY, Instant.parse("2026-11-05T00:00:00Z"),
        BigDecimal("108000000"), BigDecimal("108903000"), BigDecimal("107850000"), BigDecimal(close),
        BigDecimal("387.62580322"), BigDecimal(amount),
    )
}
