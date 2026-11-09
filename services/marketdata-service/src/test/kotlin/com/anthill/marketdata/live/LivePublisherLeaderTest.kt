package com.anthill.marketdata.live

import com.anthill.contract.CandleState
import com.anthill.contract.CandleUnit
import com.anthill.contract.CandleUpdateEvent
import com.anthill.contract.EventTypes
import com.anthill.contract.FeedStatusEvent
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

class LivePublisherLeaderTest {

    private val published = mutableListOf<Pair<String, Any>>()
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:20Z"), ZoneOffset.UTC)
    private val book = LiveCandleBook(clock, Duration.ofSeconds(10)) { _, _ -> }
    private var leader = false
    private var feedUp = true
    private val publisher = LivePublisher(book, TickerCache(), EventPublisher { t, _, p -> published += t to p }, clock, { leader }, { feedUp })

    private fun trade(at: String, seq: Long) = book.onTrade(TradeEvent(BTC, BigDecimal.ONE, BigDecimal.ONE, Instant.parse(at), seq))
    private fun candles() = published.filter { it.first == EventTypes.CHART_CANDLE_UPDATE }.map { it.second as CandleUpdateEvent }.filter { it.unit == CandleUnit.M1 }

    @Test
    fun `a standby keeps computing but publishes nothing`() {
        trade("2026-11-08T10:00:05Z", 1)
        publisher.onCorrected(BTC, CandleUnit.M1, listOf(FakeSource.candle(Instant.parse("2026-11-08T09:00:00Z"))), emptyList())
        publisher.tick()
        assertTrue(published.isEmpty())
        assertEquals(BigDecimal.ONE, book.current(BTC, CandleUnit.M1)!!.close)
    }

    @Test
    fun `promotion republishes current candles and closes from the handover`() {
        trade("2026-11-08T10:00:05Z", 1)
        trade("2026-11-08T10:01:05Z", 2)
        publisher.tick()
        leader = true
        publisher.onPromoted()
        publisher.tick()
        assertEquals(setOf(CandleState.CLOSED, CandleState.LIVE), candles().map { it.state }.toSet())
    }

    @Test
    fun `corrections from standby time are not replayed after promotion`() {
        publisher.onCorrected(BTC, CandleUnit.M1, listOf(FakeSource.candle(Instant.parse("2026-11-08T09:00:00Z"))), emptyList())
        publisher.tick()
        leader = true
        publisher.tick()
        assertEquals(emptyList(), candles())
    }

    @Test
    fun `the leader reports feed status on every tick`() {
        leader = true
        publisher.tick()
        feedUp = false
        publisher.tick()
        assertEquals(listOf(true, false), published.filter { it.first == EventTypes.CHART_FEED_STATUS }.map { (it.second as FeedStatusEvent).connected })
    }
}
