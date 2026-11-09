package com.anthill.marketdata.live

import com.anthill.contract.StreamStatus
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 소스별 스트림 연결 상태. 어댑터가 여러 대면 대마다 따로 보고, 한 대라도 붙어 있으면 그 소스는 끊긴 적이 없는 것이다.
 * 소스 전체의 연결·끊김이 바뀔 때만 등록된 리스너를 부른다(복구·장애 구간 기록).
 * 끊김 이벤트를 못 받는 경우도 잡는다:
 * - 연결된 채로 새 연결 시각이 오면(어댑터 프로세스가 죽었다 다시 뜸) 마지막으로 이벤트를 받은 시각에 끊긴 것으로 본다.
 * - [expire]: 제한 시간 동안 아무 이벤트도 없으면(어댑터는 1초마다 상태를 알린다) 마지막 이벤트 시각에 끊긴 것으로 본다.
 * 연결 시각은 [startedAt](marketdata 시작)보다 이르지 않게 본다. 꺼져 있던 동안의 체결은 DB에 없기 때문이다.
 */
class StreamStatusTracker(private val startedAt: Instant = Instant.EPOCH) {
    private data class Feed(val source: String, val instance: String)

    private val statuses = ConcurrentHashMap<Feed, StreamStatus>()
    private val lastSeen = ConcurrentHashMap<Feed, Instant>()
    private val since = ConcurrentHashMap<String, Instant>()
    private val connected = CopyOnWriteArrayList<(String, Instant) -> Unit>()
    private val disconnected = CopyOnWriteArrayList<(String, Instant) -> Unit>()

    @Synchronized
    fun update(source: String, status: StreamStatus, at: Instant = status.since ?: Instant.now(), instance: String = DEFAULT) {
        val feed = Feed(source, instance)
        val incoming = status.since
        val s = if (status.connected && incoming != null) status.copy(since = maxOf(incoming, startedAt)) else status
        val previous = statuses.put(feed, s)
        val alone = statuses.none { (f, v) -> f != feed && f.source == source && v.connected }
        when {
            s.connected && previous?.connected == true && previous.since != s.since -> if (alone) {
                disconnected.forEach { it(source, lastSeen[feed] ?: previous.since ?: at) }
                up(source, s.since ?: at)
            }
            s.connected && previous?.connected != true -> if (alone) up(source, s.since ?: at)
            !s.connected && previous?.connected == true -> if (alone) {
                since.remove(source)
                disconnected.forEach { it(source, at) }
            }
        }
        if (s.connected) touch(source, at, instance)
    }

    private fun up(source: String, at: Instant) {
        since[source] = at
        connected.forEach { it(source, at) }
    }

    /** 이 소스에서 이벤트를 받았다. */
    fun touch(source: String, at: Instant, instance: String = DEFAULT) {
        lastSeen.merge(Feed(source, instance), at) { a, b -> maxOf(a, b) }
    }

    /** 연결된 어댑터 중 [timeout] 넘게 소식이 없는 것을 마지막 이벤트 시각에 끊긴 것으로 바꾼다. */
    @Synchronized
    fun expire(now: Instant, timeout: java.time.Duration) {
        for ((feed, status) in statuses) {
            if (!status.connected) continue
            val seen = lastSeen[feed] ?: continue
            if (java.time.Duration.between(seen, now) > timeout) update(feed.source, StreamStatus(false, null), seen, feed.instance)
        }
    }

    fun connectedSince(source: String): Instant? = since[source]

    fun onConnected(listener: (source: String, at: Instant) -> Unit) { connected += listener }

    fun onDisconnected(listener: (source: String, at: Instant) -> Unit) { disconnected += listener }

    companion object {
        const val DEFAULT = "default"
    }
}
