package com.anthill.binance.api

import com.anthill.binance.FakeRest
import com.anthill.binance.stream.DayBook
import com.anthill.binance.stream.StreamControl
import com.anthill.contract.ContractJson
import com.anthill.contract.StreamStatus
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
        override fun resubscribe(symbols: List<String>) {}
        override fun pause(d: Duration) { paused = d }
        override val status = StreamStatus(true, Instant.parse("2026-11-08T00:00:00Z"))
    }
    private val mvc = MockMvcBuilders.standaloneSetup(SourceController(FakeRest(), stream, DayBook()), AdminController(stream))
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
        mvc.post("/admin/stream/pause?seconds=20").andExpect { status { isAccepted() } }
        assertEquals(Duration.ofSeconds(20), paused)
    }
}
