package com.anthill.binance

import com.anthill.contract.InstrumentId
import com.anthill.contract.SecondCandle
import com.anthill.contract.SecondCandleEvent
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertTrue

@SpringBootTest
class BinanceAdapterApplicationTests(@Autowired private val mapper: JsonMapper) {

    @Test
    fun `application json mapper writes decimals as strings`() {
        val s = SecondCandle(InstrumentId.parse("BINANCE:BTCUSDT"), Instant.parse("2026-11-10T11:23:01Z"),
            BigDecimal("74543.98000000"), BigDecimal("2"), BigDecimal("1"), BigDecimal("2"), BigDecimal("0.08711000"), BigDecimal("1"))
        val json = mapper.writeValueAsString(SecondCandleEvent(s))
        assertTrue(json.contains("\"open\":\"74543.98\""), json)
        assertTrue(json.contains("\"volume\":\"0.08711\""), json)
        assertTrue(json.contains("\"instrument\":\"BINANCE:BTCUSDT\""), json)
    }
}
