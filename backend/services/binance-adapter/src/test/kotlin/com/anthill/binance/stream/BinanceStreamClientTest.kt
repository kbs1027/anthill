package com.anthill.binance.stream

import com.anthill.binance.rest.BinanceJson
import com.anthill.binance.rest.FixtureServer
import com.anthill.contract.StreamStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinanceStreamClientTest {

    private class Recorder : StreamListener {
        val events = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<BinanceKlineMsg>()
        override fun onMessage(m: BinanceKlineMsg) { messages += m }
        override fun onConnected(at: Instant) { events += "connected" }
        override fun onDisconnected(at: Instant) { events += "disconnected" }
    }

    private fun eventually(timeoutMs: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(5)
        check(cond()) { "condition not met" }
    }

    private fun client(recorder: Recorder, connector: FakeConnector, sendInterval: Duration = Duration.ofMillis(10)) = BinanceStreamClient(
        recorder, Clock.systemUTC(), connector::connect,
        backoffBase = Duration.ofMillis(50), backoffMax = Duration.ofMillis(200), sendInterval = sendInterval,
    )

    private fun params(message: String) = BinanceJson.mapper.readTree(message).let { n -> n["method"].asString() to (0 until n["params"].size()).map { n["params"][it].asString() } }

    @Test
    fun `subscribes 200 streams per message 250ms apart on open`() {
        val rec = Recorder(); val conn = FakeConnector()
        val client = client(rec, conn, sendInterval = Duration.ofMillis(250))
        client.start((1..450).map { "S${it}USDT" })
        eventually { conn.sockets.size == 1 && conn.sockets[0].sent.size == 3 }
        val ws = conn.sockets[0]
        assertEquals(listOf(200, 200, 50), ws.sent.map { params(it).second.size })
        assertTrue(ws.sent.all { params(it).first == "SUBSCRIBE" })
        ws.sentAt.zipWithNext().forEach { (a, b) -> assertTrue((b - a) / 1_000_000 >= 240, "간격 ${(b - a) / 1_000_000}ms") }
        client.close()
    }

    @Test
    fun `resubscribe sends only the difference`() {
        val rec = Recorder(); val conn = FakeConnector()
        val client = client(rec, conn)
        client.start(listOf("AUSDT", "BUSDT"))
        eventually { conn.sockets.size == 1 && conn.sockets[0].sent.size == 1 }
        client.resubscribe(listOf("BUSDT", "CUSDT"))
        eventually { conn.sockets[0].sent.size == 3 }
        assertEquals("SUBSCRIBE" to listOf("cusdt@kline_1s"), params(conn.sockets[0].sent[1]))
        assertEquals("UNSUBSCRIBE" to listOf("ausdt@kline_1s"), params(conn.sockets[0].sent[2]))
        client.resubscribe(listOf("BUSDT", "CUSDT"))
        Thread.sleep(50)
        assertEquals(3, conn.sockets[0].sent.size, "바뀐 게 없으면 보내지 않는다")
        client.close()
    }

    @Test
    fun `joins fragmented text frames before parsing`() {
        val rec = Recorder(); val conn = FakeConnector()
        val client = client(rec, conn)
        client.start(listOf("BTCUSDT"))
        eventually { conn.listeners.size == 1 }
        val frame = FixtureServer.fixture("stream-kline-1s.json").trim()
        conn.listeners[0].onText(conn.sockets[0], frame.substring(0, 40), false)
        conn.listeners[0].onText(conn.sockets[0], frame.substring(40), true)
        conn.listeners[0].onText(conn.sockets[0], """{"result":null,"id":1}""", true)
        eventually { rec.messages.size == 1 }
        assertEquals("BTCUSDT", rec.messages[0].second.instrument.symbol)
        client.close()
    }

    @Test
    fun `reconnects with backoff, resubscribes and reports status`() {
        val rec = Recorder(); val conn = FakeConnector()
        val client = client(rec, conn)
        client.start(listOf("BTCUSDT"))
        eventually { rec.events == listOf("connected") }
        val firstSince = client.status.since
        Thread.sleep(5)
        conn.listeners[0].onClose(conn.sockets[0], 1006, "gone")
        eventually { rec.events == listOf("connected", "disconnected", "connected") }
        assertEquals(2, conn.sockets.size)
        eventually { conn.sockets[1].sent.size == 1 }
        assertTrue(client.status.connected)
        assertTrue(client.status.since!!.isAfter(firstSince))
        client.close()
    }

    @Test
    fun `pause suppresses reconnect for duration`() {
        val rec = Recorder(); val conn = FakeConnector()
        val client = client(rec, conn)
        client.start(listOf("BTCUSDT"))
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

    @Test
    fun `silent connection is dropped and reconnected`() {
        val rec = Recorder(); val conn = FakeConnector()
        val client = BinanceStreamClient(
            rec, Clock.systemUTC(), conn::connect, backoffBase = Duration.ofMillis(50), backoffMax = Duration.ofMillis(200),
            idleTimeout = Duration.ofMillis(300), sendInterval = Duration.ofMillis(10),
        )
        client.start(listOf("BTCUSDT"))
        eventually { rec.events == listOf("connected") }
        eventually(timeoutMs = 3000) { rec.events.take(3) == listOf("connected", "disconnected", "connected") }
        assertTrue(conn.sockets[0].aborted)
        client.close()
    }
}
