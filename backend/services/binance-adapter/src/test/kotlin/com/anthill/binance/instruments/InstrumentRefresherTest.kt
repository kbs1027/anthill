package com.anthill.binance.instruments

import com.anthill.binance.FakeRest
import com.anthill.binance.stream.StreamControl
import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.InstrumentListEvent
import com.anthill.contract.StreamStatus
import com.anthill.eventbus.EventPublisher
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

class InstrumentRefresherTest {

    private val rest = FakeRest().apply { markets = listOf(info("BTCUSDT", "BTC")) }
    private val resubscribed = mutableListOf<List<String>>()
    private val stream = object : StreamControl {
        override fun resubscribe(symbols: List<String>) { resubscribed += symbols }
        override fun pause(d: Duration) {}
        override val status = StreamStatus(false, null)
    }
    private val published = mutableListOf<Triple<String, String, Any>>()
    private val refresher = InstrumentRefresher(rest, stream, EventPublisher { type, key, payload -> published += Triple(type, key, payload) })

    private fun info(symbol: String, base: String) = InstrumentInfo(InstrumentId.of("BINANCE", symbol), base, base)

    @Test
    fun `refresher resubscribes and publishes only on change`() {
        refresher.refresh()
        refresher.refresh()
        assertEquals(listOf(listOf("BTCUSDT")), resubscribed)
        rest.markets = listOf(info("BTCUSDT", "BTC"), info("ETHUSDT", "ETH"))
        refresher.refresh()
        assertEquals(listOf(listOf("BTCUSDT"), listOf("BTCUSDT", "ETHUSDT")), resubscribed)
        assertEquals(2, published.size)
        assertEquals(EventTypes.MARKET_INSTRUMENT, published[1].first)
        assertEquals("BINANCE", published[1].second)
        assertEquals(InstrumentListEvent("BINANCE", rest.markets), published[1].third)
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), refresher.currentSymbols())
    }
}
