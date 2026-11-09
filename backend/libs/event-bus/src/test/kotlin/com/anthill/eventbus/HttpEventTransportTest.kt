package com.anthill.eventbus

import com.anthill.contract.ContractJson
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class HttpEventTransportTest {

    private val mapper = ContractJson.mapper()

    private fun keysOf(body: String): List<String> {
        val array = mapper.readTree(body)
        return (0 until array.size()).map { array.get(it).get("key").asString() }
    }

    private fun transport(subs: List<Subscriber>, onDropped: (Int) -> Unit = {}) = HttpEventTransport(
        producer = "test", subscribers = subs, mapper = mapper, clock = Clock.systemUTC(),
        flushInterval = Duration.ofMillis(100), maxRetries = 3, retryBase = Duration.ofMillis(10), onDropped = onDropped,
    )

    @Test
    fun `publishes within flush interval as one batch`() {
        RecordingServer().use { server ->
            transport(listOf(Subscriber(listOf("market."), server.url))).use { t ->
                (1..5).forEach { t.publish("market.trade.v1", "k$it", mapOf("n" to it)) }
                eventually { server.calls.get() == 1 }
                Thread.sleep(200)
                assertEquals(1, server.calls.get())
                assertEquals((1..5).map { "k$it" }, keysOf(server.bodies.single()))
                val first = mapper.readTree(server.bodies.single())[0]
                assertEquals("market.trade.v1", first["type"].asString())
                assertEquals(1, first["version"].asInt())
                assertEquals("test", first["producer"].asString())
            }
        }
    }

    @Test
    fun `routes by type prefix`() {
        RecordingServer().use { a ->
            RecordingServer().use { b ->
                transport(listOf(Subscriber(listOf("market."), a.url), Subscriber(listOf("chart."), b.url))).use { t ->
                    t.publish("market.trade.v1", "m", mapOf("x" to 1))
                    t.publish("chart.candle-update.v1", "c", mapOf("x" to 2))
                    eventually { a.calls.get() == 1 && b.calls.get() == 1 }
                    assertEquals(listOf("m"), keysOf(a.bodies.single()))
                    assertEquals(listOf("c"), keysOf(b.bodies.single()))
                }
            }
        }
    }

    @Test
    fun `retries then drops and reports`() {
        val dropped = AtomicInteger()
        RecordingServer(status = { 500 }).use { server ->
            transport(listOf(Subscriber(listOf("market."), server.url))) { dropped.addAndGet(it) }.use { t ->
                (1..5).forEach { t.publish("market.trade.v1", "k$it", mapOf("n" to it)) }
                eventually { dropped.get() == 5 }
                assertEquals(4, server.calls.get())
            }
        }
    }

    @Test
    fun `keeps order across batches for one subscriber`() {
        RecordingServer(status = { if (it == 1) 500 else 202 }, delayMs = { if (it == 1) 150 else 0 }).use { server ->
            transport(listOf(Subscriber(listOf("market."), server.url))).use { t ->
                (1..3).forEach { t.publish("market.trade.v1", "k$it", mapOf("n" to it)) }
                eventually { server.calls.get() == 1 }
                (4..5).forEach { t.publish("market.trade.v1", "k$it", mapOf("n" to it)) }
                eventually { server.okBodies.sumOf { keysOf(it).size } == 5 }
                assertEquals((1..5).map { "k$it" }, server.okBodies.flatMap { keysOf(it) })
            }
        }
    }
}
