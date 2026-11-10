package com.anthill.contract

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractJsonTest {

    private val mapper = ContractJson.mapper()
    private val btc = InstrumentId.parse("UPBIT:KRW-BTC")

    private val candle = Candle(
        btc, CandleUnit.M1, Instant.parse("2026-11-05T11:23:00Z"),
        BigDecimal("108328000.00000000"), BigDecimal("108330000"), BigDecimal("108300000"), BigDecimal("108310000"),
        BigDecimal("0.28308638"), BigDecimal("41983565546.3922900000000000"),
    )

    @Test
    fun `candle json keeps decimals as exact strings`() {
        val json = mapper.writeValueAsString(candle)
        assertTrue(json.contains("\"amount\":\"41983565546.39229\""), json)
        assertTrue(json.contains("\"time\":\"2026-11-05T11:23:00Z\""), json)
        assertTrue(json.contains("\"instrument\":\"UPBIT:KRW-BTC\""), json)
        assertTrue(json.contains("\"unit\":\"1m\""), json)
        val back = mapper.readValue(json, Candle::class.java)
        assertTrue(back.sameValuesAs(candle))
    }

    @Test
    fun `json never emits numbers for decimals`() {
        val trade = TradeEvent(btc, BigDecimal("108310000"), BigDecimal("0.0001"), Instant.parse("2026-11-05T11:23:01.123Z"), 17_623_512_345L)
        val json = mapper.writeValueAsString(trade)
        assertFalse(Regex("\"(price|volume)\":[0-9]").containsMatchIn(json), json)
        assertEquals(trade, mapper.readValue(json, TradeEvent::class.java))
    }

    @Test
    fun `candle state is written lowercase`() {
        val event = CandleUpdateEvent(btc, CandleUnit.M1, candle.start, candle, CandleState.CORRECTED)
        val json = mapper.writeValueAsString(event)
        assertTrue(json.contains("\"state\":\"corrected\""), json)
        assertEquals(CandleState.CORRECTED, mapper.readValue(json, CandleUpdateEvent::class.java).state)
    }

    @Test
    fun `second candle event round trips with decimals as strings`() {
        val event = SecondCandleEvent(
            SecondCandle(
                InstrumentId.parse("BINANCE:BTCUSDT"), Instant.parse("2026-11-10T12:00:01Z"),
                BigDecimal("74430.01000000"), BigDecimal("74430.02"), BigDecimal("74430.00"), BigDecimal("74430.02"),
                BigDecimal("0.00683000"), BigDecimal("508.35703230"),
            ),
        )
        val json = mapper.writeValueAsString(event)
        assertTrue(json.contains("\"start\":\"2026-11-10T12:00:01Z\""), json)
        assertTrue(json.contains("\"open\":\"74430.01\""), json)
        assertFalse(Regex("\"(open|high|low|close|volume|amount)\":[0-9]").containsMatchIn(json), json)
        val back = mapper.readValue(json, SecondCandleEvent::class.java)
        assertEquals(0, back.candle.amount.compareTo(event.candle.amount))
        assertEquals(event.candle.instrument, back.candle.instrument)
    }

    @Test
    fun `decimal numbers are read exactly`() {
        val json = """{"instrument":"UPBIT:KRW-BTC","price":108310000.10,"volume":"0.0001","tradedAt":"2026-11-05T11:23:01Z","sequentialId":1}"""
        assertEquals(BigDecimal("108310000.10"), mapper.readValue(json, TradeEvent::class.java).price)
    }
}
