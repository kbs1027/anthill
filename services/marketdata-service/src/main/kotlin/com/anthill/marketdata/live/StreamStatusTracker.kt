package com.anthill.marketdata.live

import com.anthill.contract.StreamStatus
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 소스별 스트림 연결 상태. 연결·끊김이 바뀌면 등록된 리스너를 부른다(복구·장애 구간 기록).
 * 끊김 이벤트를 못 받는 경우도 잡는다:
 * - 연결된 채로 새 연결 시각이 오면(어댑터 프로세스가 죽었다 다시 뜸) 마지막으로 이벤트를 받은 시각에 끊긴 것으로 본다.
 * - [expire]: 제한 시간 동안 아무 이벤트도 없으면(어댑터는 5초마다 상태를 알린다) 마지막 이벤트 시각에 끊긴 것으로 본다.
 * 연결 시각은 [startedAt](marketdata 시작)보다 이르지 않게 본다. 꺼져 있던 동안의 체결은 DB에 없기 때문이다.
 */
class StreamStatusTracker(private val startedAt: Instant = Instant.EPOCH) {
    private val statuses = ConcurrentHashMap<String, StreamStatus>()
    private val lastSeen = ConcurrentHashMap<String, Instant>()
    private val connected = CopyOnWriteArrayList<(String, Instant) -> Unit>()
    private val disconnected = CopyOnWriteArrayList<(String, Instant) -> Unit>()

    @Synchronized
    fun update(source: String, status: StreamStatus, at: Instant = status.since ?: Instant.now()) {
        val since = status.since
        val s = if (status.connected && since != null) status.copy(since = maxOf(since, startedAt)) else status
        val previous = statuses.put(source, s)
        when {
            s.connected && previous?.connected == true && previous.since != s.since -> {
                disconnected.forEach { it(source, lastSeen[source] ?: previous.since ?: at) }
                connected.forEach { it(source, s.since ?: at) }
            }
            s.connected && previous?.connected != true -> connected.forEach { it(source, s.since ?: at) }
            !s.connected && previous?.connected == true -> disconnected.forEach { it(source, at) }
        }
        if (s.connected) touch(source, at)
    }

    /** 이 소스에서 이벤트를 받았다. */
    fun touch(source: String, at: Instant) {
        lastSeen.merge(source, at) { a, b -> maxOf(a, b) }
    }

    /** 연결된 소스 중 [timeout] 넘게 소식이 없는 것을 마지막 이벤트 시각에 끊긴 것으로 바꾼다. */
    @Synchronized
    fun expire(now: Instant, timeout: java.time.Duration) {
        for ((source, status) in statuses) {
            if (!status.connected) continue
            val seen = lastSeen[source] ?: continue
            if (java.time.Duration.between(seen, now) > timeout) update(source, StreamStatus(false, null), seen)
        }
    }

    fun connectedSince(source: String): Instant? = statuses[source]?.takeIf { it.connected }?.since

    fun onConnected(listener: (source: String, at: Instant) -> Unit) { connected += listener }

    fun onDisconnected(listener: (source: String, at: Instant) -> Unit) { disconnected += listener }
}
