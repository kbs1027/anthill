package com.anthill.binance.stream

import com.anthill.binance.rest.TradingDay
import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentId
import com.anthill.contract.SecondCandle
import com.anthill.contract.SecondCandleEvent
import com.anthill.contract.StreamStatusEvent
import com.anthill.contract.TickerEvent
import com.anthill.eventbus.EventPublisher
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamEventBridgeTest {

    private val btc = InstrumentId.parse("BINANCE:BTCUSDT")
    private val published = mutableListOf<Triple<String, String, Any>>()
    private val dayBook = DayBook()
    private val bridge = StreamEventBridge(EventPublisher { type, key, payload -> published += Triple(type, key, payload) }, dayBook)

    private fun second(at: String, volume: String) =
        SecondCandle(btc, Instant.parse(at), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal(volume), BigDecimal(volume))

    @Test
    fun `second candle is published by instrument and a changed day adds a ticker`() {
        val day = Instant.parse("2026-11-10T00:00:00Z")
        dayBook.anchor(listOf(TradingDay("BTCUSDT", Candle(btc, CandleUnit.DAY, day, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE), 5)))

        val traded = BinanceKlineMsg(second("2026-11-10T10:00:00Z", "2"), 6, 7)
        bridge.onMessage(traded)
        assertEquals(Triple(EventTypes.MARKET_SECOND_CANDLE, "BINANCE:BTCUSDT", SecondCandleEvent(traded.second) as Any), published[0])
        assertEquals(EventTypes.MARKET_TICKER, published[1].first)
        assertEquals("BINANCE:BTCUSDT", published[1].second)
        assertTrue(BigDecimal.TEN.compareTo((published[1].third as TickerEvent).ticker.tradePrice) == 0)

        published.clear()
        bridge.onMessage(BinanceKlineMsg(second("2026-11-10T10:00:01Z", "0"), -1, -1))
        assertEquals(listOf(EventTypes.MARKET_SECOND_CANDLE), published.map { it.first }, "거래 없는 초는 티커를 내지 않는다")
        assertEquals(2, bridge.drainReceived())
    }

    @Test
    fun `stream status uses the BINANCE key`() {
        bridge.onConnected(Instant.parse("2026-11-08T00:00:01Z"))
        bridge.onDisconnected(Instant.parse("2026-11-08T00:00:02Z"))
        assertEquals(Triple(EventTypes.MARKET_STREAM_STATUS, "BINANCE", StreamStatusEvent("BINANCE", true, Instant.parse("2026-11-08T00:00:01Z")) as Any), published[0])
        assertEquals(Triple(EventTypes.MARKET_STREAM_STATUS, "BINANCE", StreamStatusEvent("BINANCE", false, Instant.parse("2026-11-08T00:00:02Z")) as Any), published[1])
    }
}
