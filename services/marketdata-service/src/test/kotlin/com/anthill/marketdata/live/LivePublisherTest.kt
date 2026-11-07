package com.anthill.marketdata.live

import com.anthill.contract.CandleState
import com.anthill.contract.CandleUnit
import com.anthill.contract.CandleUpdateEvent
import com.anthill.contract.EventTypes
import com.anthill.contract.TradeEvent
import com.anthill.eventbus.EventPublisher
import com.anthill.marketdata.FakeSource
import com.anthill.marketdata.FakeSource.Companion.BTC
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LivePublisherTest {

    private val published = mutableListOf<Triple<String, String, Any>>()
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:20Z"), ZoneOffset.UTC)
    private val book = LiveCandleBook(clock, Duration.ofSeconds(10)) {}
    private val tickers = TickerCache()
    private val publisher = LivePublisher(book, tickers, EventPublisher { t, k, p -> published += Triple(t, k, p) }, clock)

    @Test
    fun `publisher emits only changed and nothing without trades`() {
        publisher.tick()
        assertTrue(published.isEmpty())
        book.onTrade(TradeEvent(BTC, BigDecimal.ONE, BigDecimal.ONE, Instant.parse("2026-11-08T10:00:05Z"), 1))
        book.onTrade(TradeEvent(BTC, BigDecimal.TEN, BigDecimal.ONE, Instant.parse("2026-11-08T10:00:06Z"), 2))
        publisher.tick()
        val m1 = published.map { it.third as CandleUpdateEvent }.filter { it.unit == CandleUnit.M1 }
        assertEquals(1, m1.size)
        assertEquals(CandleState.LIVE, m1.single().state)
        assertTrue(published.all { it.first == EventTypes.CHART_CANDLE_UPDATE && it.second == BTC.value })
        published.clear()
        publisher.tick()
        assertTrue(published.isEmpty())
    }

    @Test
    fun `corrections are published as corrected and removed`() {
        val fixed = FakeSource.candle(Instant.parse("2026-11-08T09:00:00Z"))
        publisher.onCorrected(BTC, CandleUnit.M1, listOf(fixed), listOf(Instant.parse("2026-11-08T09:01:00Z")))
        publisher.tick()
        val events = published.map { it.third as CandleUpdateEvent }
        assertEquals(listOf(CandleState.CORRECTED, CandleState.REMOVED), events.map { it.state })
        assertEquals(fixed, events[0].candle)
        assertEquals(null, events[1].candle)
    }
}
