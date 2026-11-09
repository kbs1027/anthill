package com.anthill.marketdata.ingest

import com.anthill.contract.CandleUnit
import com.anthill.contract.ContractJson
import com.anthill.contract.Decimals
import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentId
import com.anthill.contract.TradeEvent
import com.anthill.eventbus.EventEnvelope
import com.anthill.marketdata.PgTest
import com.anthill.marketdata.live.LiveCandleBook
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue

@AutoConfigureMockMvc
class EventsControllerTest : PgTest() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var book: LiveCandleBook

    @Test
    fun `duplicate trade batch is applied once`() {
        val id = InstrumentId.parse("UPBIT:KRW-DUP")
        val mapper = ContractJson.mapper()
        val trade = TradeEvent(id, BigDecimal("100"), BigDecimal("0.5"), Instant.parse("2026-11-08T10:00:05Z"), 1)
        val envelope = EventEnvelope(UUID.randomUUID(), EventTypes.MARKET_TRADE, 1, id.value, Instant.now(), "upbit-adapter", mapper.valueToTree(trade))
        val body = mapper.writeValueAsString(listOf(envelope))
        repeat(2) {
            mvc.post("/internal/events") { contentType = MediaType.APPLICATION_JSON; content = body }.andExpect { status { isAccepted() } }
        }
        assertTrue(Decimals.same(BigDecimal("0.5"), book.current(id, CandleUnit.M1)!!.volume))
    }
}
