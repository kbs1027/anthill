package com.anthill.upbit.api

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.ContractJson
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import com.anthill.contract.TickerSnapshot
import com.anthill.upbit.rest.UpbitRestClient
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals

class SourceControllerTest {

    private val btc = InstrumentId.parse("UPBIT:KRW-BTC")

    private class FakeRest(private val btc: InstrumentId) : UpbitRestClient {
        var lastCall: List<Any?> = emptyList()
        override fun markets() = listOf(InstrumentInfo(btc, "비트코인", "Bitcoin"))
        override fun candles(market: String, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle> {
            lastCall = listOf(market, unit, to, count, priority)
            return listOf(
                Candle(btc, unit, Instant.parse("2026-11-05T11:24:00Z"), BigDecimal("1.0"), BigDecimal("2"), BigDecimal("1"), BigDecimal("2"), BigDecimal("0.5"), BigDecimal("1.00")),
                Candle(btc, unit, Instant.parse("2026-11-05T11:23:00Z"), BigDecimal("1"), BigDecimal("1"), BigDecimal("1"), BigDecimal("1"), BigDecimal("0.1"), BigDecimal("0.1")),
            )
        }
        override fun tickers(): List<TickerSnapshot> = emptyList()
    }

    private val rest = FakeRest(btc)
    private val mvc = MockMvcBuilders.standaloneSetup(SourceController(rest))
        .setMessageConverters(JacksonJsonHttpMessageConverter(ContractJson.mapper()))
        .build()

    @Test
    fun `internal candles returns strings newest first`() {
        mvc.get("/internal/candles?instrument=UPBIT:KRW-BTC&unit=1m&to=2026-11-05T11:25:00Z&count=2&priority=USER").andExpect {
            status { isOk() }
            jsonPath("$[0].time") { value("2026-11-05T11:24:00Z") }
            jsonPath("$[0].open") { value("1") }
            jsonPath("$[0].amount") { value("1") }
            jsonPath("$[1].time") { value("2026-11-05T11:23:00Z") }
        }
        assertEquals(listOf("KRW-BTC", CandleUnit.M1, Instant.parse("2026-11-05T11:25:00Z"), 2, Priority.USER), rest.lastCall)
    }

    @Test
    fun `rejects non upbit instrument`() {
        mvc.get("/internal/candles?instrument=KRX:005930&unit=1m&count=2").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `rejects bad unit and count`() {
        mvc.get("/internal/candles?instrument=UPBIT:KRW-BTC&unit=2m&count=2").andExpect { status { isBadRequest() } }
        mvc.get("/internal/candles?instrument=UPBIT:KRW-BTC&unit=1m&count=201").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `instruments lists ids as strings`() {
        mvc.get("/internal/instruments").andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value("UPBIT:KRW-BTC") }
        }
    }
}
