package com.anthill.marketdata.api

import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentInfo
import com.anthill.marketdata.FakeSource
import com.anthill.marketdata.PgTest
import com.anthill.marketdata.candle.InstrumentRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant

@AutoConfigureMockMvc
class CandleControllerTest : PgTest() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var instruments: InstrumentRepository

    @BeforeEach
    fun seed() {
        instruments.sync("UPBIT", listOf(InstrumentInfo(FakeSource.BTC, "비트코인", "Bitcoin")), Instant.now())
        source.put(FakeSource.series(Instant.parse("2026-11-05T00:00:00Z"), 3))
    }

    @Test
    fun `returns decimal strings ascending`() {
        mvc.get("/api/candles?market=UPBIT:KRW-BTC&unit=1m&to=2026-11-05T00:03:00Z&count=2").andExpect {
            status { isOk() }
            jsonPath("$.market") { value("UPBIT:KRW-BTC") }
            jsonPath("$.unit") { value("1m") }
            jsonPath("$.candles[0].time") { value("2026-11-05T00:01:00Z") }
            jsonPath("$.candles[1].time") { value("2026-11-05T00:02:00Z") }
            jsonPath("$.candles[1].close") { value("102") }
            jsonPath("$.live") { doesNotExist() }
        }
    }

    @Test
    fun `rejects count 201`() {
        mvc.get("/api/candles?market=UPBIT:KRW-BTC&unit=1m&count=201").andExpect { status { isBadRequest() } }
        mvc.get("/api/candles?market=UPBIT:KRW-BTC&unit=7m&count=1").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `unknown market is 404`() {
        mvc.get("/api/candles?market=UPBIT:KRW-NOPE&unit=1m&count=1").andExpect { status { isNotFound() } }
    }

    @Suppress("unused") private val unit = CandleUnit.M1
}
