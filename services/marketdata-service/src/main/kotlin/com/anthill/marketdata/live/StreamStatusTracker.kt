package com.anthill.marketdata.live

import com.anthill.contract.StreamStatus
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 소스별 스트림 연결 상태. 연결·끊김이 바뀌면 등록된 리스너를 부른다(복구·장애 구간 기록). */
class StreamStatusTracker {
    private val statuses = ConcurrentHashMap<String, StreamStatus>()
    private val connected = CopyOnWriteArrayList<(String, Instant) -> Unit>()
    private val disconnected = CopyOnWriteArrayList<(String, Instant) -> Unit>()

    fun update(source: String, status: StreamStatus, at: Instant = status.since ?: Instant.now()) {
        val previous = statuses.put(source, status)
        when {
            status.connected && (previous?.connected != true || previous.since != status.since) -> connected.forEach { it(source, status.since ?: at) }
            !status.connected && previous?.connected == true -> disconnected.forEach { it(source, at) }
        }
    }

    fun connectedSince(source: String): Instant? = statuses[source]?.takeIf { it.connected }?.since

    fun onConnected(listener: (source: String, at: Instant) -> Unit) { connected += listener }

    fun onDisconnected(listener: (source: String, at: Instant) -> Unit) { disconnected += listener }
}
