package com.anthill.marketdata.live

import com.anthill.contract.StreamStatus
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 리뷰 I5: 스트림이 살아 있는지를 추측하지 않고 마지막으로 받은 이벤트로 판단한다. */
class StreamStatusTrackerTest {

    private fun at(s: String) = Instant.parse(s)
    private val connects = mutableListOf<Instant>()
    private val disconnects = mutableListOf<Instant>()
    private fun tracker(startedAt: Instant = Instant.EPOCH) = StreamStatusTracker(startedAt).also { t ->
        t.onConnected { _, a -> connects += a }
        t.onDisconnected { _, a -> disconnects += a }
    }

    @Test
    fun `new since while connected counts as a disconnect at the last event`() {
        // 어댑터 프로세스가 죽으면 끊김 이벤트를 못 보낸다. 다시 켜진 어댑터의 새 연결 시각으로 알아챈다.
        val t = tracker()
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:00:00Z")))
        t.touch("UPBIT", at("2026-11-08T10:05:00Z"))
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:07:30Z")))
        assertEquals(listOf(at("2026-11-08T10:05:00Z")), disconnects)
        assertEquals(listOf(at("2026-11-08T10:00:00Z"), at("2026-11-08T10:07:30Z")), connects)
    }

    @Test
    fun `silence beyond the timeout marks the source disconnected`() {
        val t = tracker()
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:00:00Z")))
        t.touch("UPBIT", at("2026-11-08T10:05:00Z"))
        t.expire(at("2026-11-08T10:05:19Z"), Duration.ofSeconds(20))
        assertEquals(emptyList(), disconnects)
        t.expire(at("2026-11-08T10:05:21Z"), Duration.ofSeconds(20))
        assertEquals(listOf(at("2026-11-08T10:05:00Z")), disconnects)
        assertNull(t.connectedSince("UPBIT"))
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T10:00:00Z")))       // 어댑터 상태 알림이 다시 오면 연결로 본다
        assertEquals(2, connects.size)
    }

    @Test
    fun `since never predates marketdata start`() {
        // marketdata가 꺼져 있던 동안은 DB에 체결이 없다. 그 앞부터 연결돼 있었다고 믿으면 빈 구간을 그대로 보여 준다.
        val t = tracker(startedAt = at("2026-11-08T10:30:00Z"))
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T09:00:00Z")))
        assertEquals(at("2026-11-08T10:30:00Z"), t.connectedSince("UPBIT"))
        t.update("UPBIT", StreamStatus(true, at("2026-11-08T09:00:00Z")))       // 같은 상태가 다시 와도 재연결로 보지 않는다
        assertEquals(1, connects.size)
        assertEquals(emptyList(), disconnects)
    }
}
