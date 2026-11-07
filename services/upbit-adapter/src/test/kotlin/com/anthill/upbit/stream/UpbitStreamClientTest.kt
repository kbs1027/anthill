package com.anthill.upbit.stream

import com.anthill.contract.StreamStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpbitStreamClientTest {

    private class Recorder : StreamListener {
        val events = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<UpbitMessage>()
        override fun onMessage(m: UpbitMessage) { messages += m }
        override fun onConnected(at: Instant) { events += "connected" }
        override fun onDisconnected(at: Instant) { events += "disconnected" }
    }

    private fun eventually(timeoutMs: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(5)
        check(cond()) { "condition not met" }
    }

    private fun client(recorder: Recorder, connector: FakeConnector) = UpbitStreamClient(
        recorder, Clock.systemUTC(), connector::connect, backoffBase = Duration.ofMillis(50), backoffMax = Duration.ofMillis(200),
    )

    @Test
    fun `sends one subscribe message on open and parses binary frames`() {
        val rec = Recorder(); val conn = FakeConnector()
        val client = client(rec, conn)
        client.start(listOf("KRW-BTC"))
        eventually { conn.sockets.size == 1 && conn.sockets[0].sent.size == 1 }
        assertTrue(conn.sockets[0].sent[0].contains("candle.240m"))
        val frame = """{"type":"trade","code":"KRW-BTC","trade_timestamp":1794071474532,"trade_price":1,"trade_volume":2,"sequential_id":7}"""
        val bytes = frame.toByteArray()
        conn.listeners[0].onBinary(conn.sockets[0], java.nio.ByteBuffer.wrap(bytes, 0, 10), false)
        conn.listeners[0].onBinary(conn.sockets[0], java.nio.ByteBuffer.wrap(bytes, 10, bytes.size - 10), true)
        eventually { rec.messages.size == 1 }
        client.close()
    }

    @Test
    fun `reconnects with backoff and reports status`() {
        val rec = Recorder(); val conn = FakeConnector()
        val client = client(rec, conn)
        client.start(listOf("KRW-BTC"))
        eventually { rec.events == listOf("connected") }
        val firstSince = client.status.since
        Thread.sleep(5)
        conn.listeners[0].onClose(conn.sockets[0], 1006, "gone")
        eventually { rec.events == listOf("connected", "disconnected", "connected") }
        assertEquals(2, conn.sockets.size)
        assertTrue(client.status.connected)
        assertTrue(client.status.since!!.isAfter(firstSince))
        client.close()
    }

    @Test
    fun `pause suppresses reconnect for duration`() {
        val rec = Recorder(); val conn = FakeConnector()
        val client = client(rec, conn)
        client.start(listOf("KRW-BTC"))
        eventually { conn.sockets.size == 1 }
        client.pause(Duration.ofMillis(400))
        eventually { rec.events.contains("disconnected") }
        assertTrue(conn.sockets[0].aborted)
        assertEquals(StreamStatus(false, null), client.status)
        Thread.sleep(250)
        assertEquals(1, conn.sockets.size)
        eventually { conn.sockets.size == 2 }
        client.close()
    }
}
