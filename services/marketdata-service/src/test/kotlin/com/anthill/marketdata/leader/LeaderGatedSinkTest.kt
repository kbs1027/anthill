package com.anthill.marketdata.leader

import com.anthill.contract.Candle
import com.anthill.marketdata.FakeSource
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals

class LeaderGatedSinkTest {

    private val start = Instant.parse("2026-11-08T10:00:00Z")
    private var now = Instant.parse("2026-11-08T10:01:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(z: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
    private var leader = false
    private val written = mutableListOf<Pair<Candle, Boolean>>()
    private val sink = LeaderGatedSink({ c, late -> written += c to late }, { leader }, clock, Duration.ofSeconds(10))
    private val candle = FakeSource.candle(start)

    @Test
    fun `a standby writes nothing`() {
        sink.onClosed(candle, false)
        assertEquals(emptyList(), written)
    }

    @Test
    fun `promotion writes the closes held in the handover window`() {
        sink.onClosed(candle, true)
        leader = true
        sink.flush()
        assertEquals(listOf(candle to true), written)
        sink.flush()
        assertEquals(1, written.size)
    }

    @Test
    fun `closes older than the window are forgotten`() {
        sink.onClosed(candle, false)
        now = now.plusSeconds(11)
        leader = true
        sink.flush()
        assertEquals(emptyList(), written)
    }

    @Test
    fun `a leader writes through`() {
        leader = true
        sink.onClosed(candle, false)
        assertEquals(listOf(candle to false), written)
    }
}
