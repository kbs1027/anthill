package com.anthill.upbit

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertTrue

@SpringBootTest
class UpbitAdapterApplicationTests(@Autowired private val mapper: JsonMapper) {

    @Test
    fun `application json mapper writes decimals as strings`() {
        val c = Candle(InstrumentId.parse("UPBIT:KRW-BTC"), CandleUnit.M1, Instant.parse("2026-11-05T11:23:00Z"),
            BigDecimal("1.50"), BigDecimal("2"), BigDecimal("1"), BigDecimal("2"), BigDecimal("0.5"), BigDecimal("1"))
        val json = mapper.writeValueAsString(c)
        assertTrue(json.contains("\"open\":\"1.5\""), json)
        assertTrue(json.contains("\"unit\":\"1m\""), json)
    }
}
