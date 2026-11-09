package com.anthill.eventbus

import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.logging.Level
import java.util.logging.Logger

/** [types]는 type 이름의 접두어 목록이다 (예: `market.`). */
data class Subscriber(val types: List<String>, val url: URI)

/**
 * 1단계 전송: 받는 곳마다 큐 하나와 스레드 하나를 두고 [flushInterval]마다 쌓인 이벤트를 JSON 배열로 POST 한다.
 * 스레드가 하나라 받는 곳별 순서가 지켜진다. 실패하면 [maxRetries]번 재시도하고, 그래도 안 되면 버린 뒤 [onDropped]로 알린다.
 * 멈춘 받는 곳의 큐는 [maxQueued]를 넘지 않는다. 시세는 최신이 중요하므로 가장 오래된 것부터 버린다.
 */
class HttpEventTransport(
    private val producer: String,
    subscribers: List<Subscriber>,
    private val mapper: JsonMapper,
    private val clock: Clock,
    private val flushInterval: Duration = Duration.ofMillis(200),
    private val maxRetries: Int = 3,
    private val retryBase: Duration = Duration.ofMillis(200),
    private val requestTimeout: Duration = Duration.ofSeconds(2),
    private val maxQueued: Int = 20_000,
    private val http: HttpClient = HttpClient.newHttpClient(),
    private val onDropped: (Int) -> Unit = {},
) : EventPublisher, AutoCloseable {

    private val log = Logger.getLogger(HttpEventTransport::class.java.name)
    private val lanes = subscribers.map { Lane(it) }

    override fun publish(type: String, key: String, payload: Any) {
        val targets = lanes.filter { lane -> lane.subscriber.types.any { type.startsWith(it) } }
        if (targets.isEmpty()) return
        val envelope = EventEnvelope(
            UUID.randomUUID(), type, versionOf(type), key, clock.instant(), producer, mapper.valueToTree(payload),
        )
        targets.forEach { it.offer(envelope) }
    }

    override fun close() = lanes.forEach { it.stop() }

    private inner class Lane(val subscriber: Subscriber) {
        val queue = LinkedBlockingQueue<EventEnvelope>()
        @Volatile private var running = true
        private val worker = Thread.ofVirtual().name("events->${subscriber.url}").start { loop() }

        fun offer(e: EventEnvelope) {
            while (queue.size >= maxQueued) {
                if (queue.poll() != null) onDropped(1)
            }
            queue.add(e)
        }

        private fun loop() {
            while (running) {
                try {
                    Thread.sleep(flushInterval)
                } catch (_: InterruptedException) {
                    if (!running) return
                }
                val batch = ArrayList<EventEnvelope>()
                queue.drainTo(batch)
                if (batch.isNotEmpty()) deliver(batch)
            }
        }

        private fun deliver(batch: List<EventEnvelope>) {
            val body = mapper.writeValueAsString(batch)
            for (attempt in 0..maxRetries) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(retryBase.multipliedBy(1L shl (attempt - 1)))
                    } catch (_: InterruptedException) {
                        if (!running) return
                    }
                }
                if (send(body)) return
            }
            log.warning("dropped ${batch.size} events for ${subscriber.url}")
            onDropped(batch.size)
        }

        private fun send(body: String): Boolean = try {
            val request = HttpRequest.newBuilder(subscriber.url)
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            log.log(Level.FINE, "event delivery failed for ${subscriber.url}", e)
            false
        }

        fun stop() {
            running = false
            worker.interrupt()
        }
    }

    private fun versionOf(type: String): Int = type.substringAfterLast(".v", "1").toIntOrNull() ?: 1
}
