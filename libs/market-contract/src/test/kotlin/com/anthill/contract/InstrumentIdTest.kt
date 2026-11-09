package com.anthill.contract

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class InstrumentIdTest {

    @Test
    fun `instrument id parses source and symbol`() {
        val id = InstrumentId.parse("UPBIT:KRW-BTC")
        assertEquals("UPBIT", id.source)
        assertEquals("KRW-BTC", id.symbol)
        assertEquals(id, InstrumentId.of("UPBIT", "KRW-BTC"))
    }

    @Test
    fun `instrument id without prefix is rejected`() {
        assertThrows<IllegalArgumentException> { InstrumentId.parse("KRW-BTC") }
        assertThrows<IllegalArgumentException> { InstrumentId.parse("UPBIT:") }
    }
}
