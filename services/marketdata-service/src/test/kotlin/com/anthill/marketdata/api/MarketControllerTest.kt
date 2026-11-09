package com.anthill.marketdata.api

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.TickerSnapshot
import com.anthill.marketdata.PgTest
import com.anthill.marketdata.candle.InstrumentRepository
import com.anthill.marketdata.live.TickerCache
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.math.BigDecimal
import java.time.Instant

@AutoConfigureMockMvc
class MarketControllerTest : PgTest() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var instruments: InstrumentRepository
    @Autowired lateinit var tickers: TickerCache

    private fun ticker(id: InstrumentId, price: String, amount: String) = TickerSnapshot(
        id, BigDecimal(price), BigDecimal("-0.0123"), BigDecimal(amount),
        Candle(id, CandleUnit.DAY, Instant.parse("2026-11-08T00:00:00Z"), BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal(price), BigDecimal.ONE, BigDecimal(price)),
    )

    @Test
    fun `markets api orders by amount24h with string numbers`() {
        val a = InstrumentId.parse("UPBIT:KRW-AAA"); val b = InstrumentId.parse("UPBIT:KRW-BBB")
        instruments.sync("UPBIT", listOf(InstrumentInfo(a, "에이", "A"), InstrumentInfo(b, "비", "B")), Instant.now())
        tickers.put(ticker(a, "10.50", "100"))
        tickers.put(ticker(b, "2000", "900.000"))
        mvc.get("/api/markets").andExpect {
            status { isOk() }
            jsonPath("$[0].market") { value("UPBIT:KRW-BBB") }
            jsonPath("$[0].amount24h") { value("900") }
            jsonPath("$[1].price") { value("10.5") }
            jsonPath("$[1].koreanName") { value("에이") }
            jsonPath("$[1].changeRate") { value("-0.0123") }
        }
    }
}
