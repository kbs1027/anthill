package com.anthill.eventbus

import com.anthill.contract.ContractJson
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/** 멈춘 수신자에게 보낼 이벤트가 끝없이 쌓이지 않게 한다. 시세는 최신이 중요하므로 가장 오래된 것부터 버린다. */
class HttpEventTransportQueueTest {

    private val mapper = ContractJson.mapper()

    @Test
    fun `a full queue drops the oldest events and reports them`() {
        val dropped = AtomicInteger()
        RecordingServer().use { server ->
            HttpEventTransport(
                producer = "test", subscribers = listOf(Subscriber(listOf("market."), server.url)), mapper = mapper, clock = Clock.systemUTC(),
                flushInterval = Duration.ofMillis(300), maxRetries = 0, retryBase = Duration.ofMillis(10), maxQueued = 3, onDropped = { dropped.addAndGet(it) },
            ).use { t ->
                (1..5).forEach { t.publish("market.trade.v1", "k$it", mapOf("n" to it)) }
                eventually { server.calls.get() == 1 }
                val body = mapper.readTree(server.bodies.single())
                assertEquals(listOf("k3", "k4", "k5"), (0 until body.size()).map { body.get(it).get("key").asString() })
                assertEquals(2, dropped.get())
            }
        }
    }
}
