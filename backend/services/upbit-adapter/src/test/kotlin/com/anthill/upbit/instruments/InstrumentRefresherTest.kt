package com.anthill.upbit.instruments

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.InstrumentListEvent
import com.anthill.contract.Priority
import com.anthill.contract.StreamStatus
import com.anthill.contract.TickerSnapshot
import com.anthill.eventbus.EventPublisher
import com.anthill.upbit.rest.UpbitRestClient
import com.anthill.upbit.stream.StreamControl
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class InstrumentRefresherTest {

    private var list = listOf(info("KRW-BTC"))
    private val rest = object : UpbitRestClient {
        override fun markets() = list
        override fun candles(market: String, unit: CandleUnit, to: Instant?, count: Int, priority: Priority) = emptyList<Candle>()
        override fun tickers() = emptyList<TickerSnapshot>()
    }
    private val resubscribed = mutableListOf<List<String>>()
    private val stream = object : StreamControl {
        override fun resubscribe(markets: List<String>) { resubscribed += markets }
        override fun pause(d: Duration) {}
        override val status = StreamStatus(false, null)
    }
    private val published = mutableListOf<Pair<String, Any>>()
    private val refresher = InstrumentRefresher(rest, stream, EventPublisher { type, _, payload -> published += type to payload })

    private fun info(m: String) = InstrumentInfo(InstrumentId.of("UPBIT", m), m, m)

    @Test
    fun `refresher resubscribes and publishes only on change`() {
        refresher.refresh()
        refresher.refresh()
        assertEquals(listOf(listOf("KRW-BTC")), resubscribed)
        list = listOf(info("KRW-BTC"), info("KRW-XRP"))
        refresher.refresh()
        assertEquals(listOf(listOf("KRW-BTC"), listOf("KRW-BTC", "KRW-XRP")), resubscribed)
        assertEquals(2, published.size)
        assertEquals(EventTypes.MARKET_INSTRUMENT, published[1].first)
        assertEquals(InstrumentListEvent("UPBIT", list), published[1].second)
        assertEquals(listOf("KRW-BTC", "KRW-XRP"), refresher.currentMarkets())
    }
}
