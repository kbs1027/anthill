package com.anthill.marketdata.live

import com.anthill.contract.StreamStatus
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 어댑터 두 대가 같은 소스를 받는다. 한 대라도 붙어 있으면 스트림은 끊긴 적이 없다. */
class StreamStatusTrackerMultiFeedTest {

    private fun at(s: String) = Instant.parse(s)
    private val events = mutableListOf<String>()
    private val t = StreamStatusTracker().also { tracker ->
        tracker.onConnected { s, a -> events += "up $s $a" }
        tracker.onDisconnected { s, a -> events += "down $s $a" }
    }

    @Test
    fun `the source stays connected while another adapter is up`() {
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:00:00Z")), instance = "a")
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:00:05Z")), instance = "b")
        t.update("UPBIT", StreamStatus(false, null), at("2026-11-08T10:01:00Z"), instance = "a")
        assertEquals(listOf("up UPBIT 2026-11-08T10:00:00Z"), events)
        assertEquals(at("2026-11-08T10:00:00Z"), t.connectedSince("UPBIT"))
    }

    @Test
    fun `the source goes down only when the last adapter does`() {
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:00:00Z")), instance = "a")
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:00:05Z")), instance = "b")
        t.update("UPBIT", StreamStatus(false, null), at("2026-11-08T10:01:00Z"), instance = "a")
        t.update("UPBIT", StreamStatus(false, null), at("2026-11-08T10:02:00Z"), instance = "b")
        assertEquals("down UPBIT 2026-11-08T10:02:00Z", events.last())
        assertNull(t.connectedSince("UPBIT"))
    }

    @Test
    fun `one silent adapter expires without touching the source`() {
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:00:00Z")), instance = "a")
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:00:00Z")), instance = "b")
        t.touch("UPBIT", at("2026-11-08T10:00:10Z"), "b")
        t.expire(at("2026-11-08T10:00:10Z"), Duration.ofSeconds(3))
        assertEquals(listOf("up UPBIT 2026-11-08T10:00:00Z"), events)
        t.expire(at("2026-11-08T10:00:20Z"), Duration.ofSeconds(3))
        assertEquals("down UPBIT 2026-11-08T10:00:10Z", events.last())
    }

    @Test
    fun `an adapter restarting beside a healthy one is not an outage`() {
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:00:00Z")), instance = "a")
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:00:00Z")), instance = "b")
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:05:00Z")), instance = "a")
        assertEquals(listOf("up UPBIT 2026-11-08T10:00:00Z"), events)
    }
}
