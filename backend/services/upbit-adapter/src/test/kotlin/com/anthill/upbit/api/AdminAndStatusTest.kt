package com.anthill.upbit.api

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.ContractJson
import com.anthill.contract.Priority
import com.anthill.contract.StreamStatus
import com.anthill.contract.TickerSnapshot
import com.anthill.upbit.rest.UpbitRestClient
import com.anthill.upbit.stream.StreamControl
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class AdminAndStatusTest {

    private var paused: Duration? = null
    private val stream = object : StreamControl {
        override fun resubscribe(markets: List<String>) {}
        override fun pause(d: Duration) { paused = d }
        override val status = StreamStatus(true, Instant.parse("2026-11-08T00:00:00Z"))
    }
    private val rest = object : UpbitRestClient {
        override fun markets() = emptyList<com.anthill.contract.InstrumentInfo>()
        override fun candles(market: String, unit: CandleUnit, to: Instant?, count: Int, priority: Priority) = emptyList<Candle>()
        override fun tickers() = emptyList<TickerSnapshot>()
    }
    private val mvc = MockMvcBuilders.standaloneSetup(SourceController(rest, stream), AdminController(stream))
        .setMessageConverters(JacksonJsonHttpMessageConverter(ContractJson.mapper()))
        .build()

    @Test
    fun `stream status endpoint reflects client`() {
        mvc.get("/internal/stream-status").andExpect {
            status { isOk() }
            jsonPath("$.connected") { value(true) }
            jsonPath("$.since") { value("2026-11-08T00:00:00Z") }
        }
    }

    @Test
    fun `pause endpoint pauses stream`() {
        mvc.post("/admin/stream/pause?seconds=300").andExpect { status { isAccepted() } }
        assertEquals(Duration.ofSeconds(300), paused)
    }
}
