package com.anthill.binance.stream

import com.anthill.contract.EventTypes
import com.anthill.contract.StreamStatus
import com.anthill.contract.StreamStatusEvent
import com.anthill.eventbus.EventPublisher
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** 어댑터가 죽으면 끊김을 알릴 수 없다. 5초마다 상태를 알려, 소식이 끊기면 marketdata가 알아채게 한다. */
class StreamHeartbeatTest {

    private val now = Instant.parse("2026-11-08T10:00:00Z")

    private class FixedStream(override val status: StreamStatus) : StreamControl {
        override fun resubscribe(symbols: List<String>) {}
        override fun pause(d: Duration) {}
    }

    @Test
    fun `beat repeats the current status with the connection time`() {
        val sent = mutableListOf<Triple<String, String, Any>>()
        val publisher = EventPublisher { t, k, p -> sent += Triple(t, k, p) }
        val since = Instant.parse("2026-11-08T09:00:00Z")
        StreamHeartbeat(FixedStream(StreamStatus(true, since)), publisher, Clock.fixed(now, ZoneOffset.UTC)).beat()
        StreamHeartbeat(FixedStream(StreamStatus(false, null)), publisher, Clock.fixed(now, ZoneOffset.UTC)).beat()
        assertEquals(
            listOf<Triple<String, String, Any>>(
                Triple(EventTypes.MARKET_STREAM_STATUS, "BINANCE", StreamStatusEvent("BINANCE", true, since)),
                Triple(EventTypes.MARKET_STREAM_STATUS, "BINANCE", StreamStatusEvent("BINANCE", false, now)),
            ),
            sent,
        )
    }
}
