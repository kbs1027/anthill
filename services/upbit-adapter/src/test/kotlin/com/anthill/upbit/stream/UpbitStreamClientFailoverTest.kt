package com.anthill.upbit.stream

import org.junit.jupiter.api.Test
import java.net.http.WebSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue

/** 차트가 멈추는 시간을 줄이려고 빨리 감지하고 빨리 다시 붙는다. 대신 열렸다 바로 닫히는 연결로 업비트를 두드리지 않는다. */
class UpbitStreamClientFailoverTest {

    private object Silent : StreamListener {
        override fun onMessage(m: UpbitMessage) {}
        override fun onConnected(at: Instant) {}
        override fun onDisconnected(at: Instant) {}
    }

    private fun eventually(timeoutMs: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(5)
        check(cond()) { "condition not met" }
    }

    @Test
    fun `a silent connection is noticed well within a second`() {
        val conn = FakeConnector()
        val client = UpbitStreamClient(
            Silent, Clock.systemUTC(), conn::connect, backoffBase = Duration.ofMillis(10), backoffMax = Duration.ofMillis(20), idleTimeout = Duration.ofMillis(100),
        )
        client.start(listOf("KRW-BTC"))
        eventually { conn.sockets.size == 1 }
        val started = System.nanoTime()
        eventually(2000) { conn.sockets[0].aborted }
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        client.close()
        assertTrue(waitedMs < 500, "took $waitedMs ms")
    }

    @Test
    fun `connections that open and close at once keep backing off`() {
        val attempts = CopyOnWriteArrayList<Long>()
        val connect: (WebSocket.Listener) -> CompletableFuture<WebSocket> = { l ->
            attempts += System.nanoTime()
            val ws = FakeWebSocket()
            l.onOpen(ws)
            l.onClose(ws, 1006, "flap")
            CompletableFuture.completedFuture(ws)
        }
        val client = UpbitStreamClient(
            Silent, Clock.systemUTC(), connect, backoffBase = Duration.ofMillis(20), backoffMax = Duration.ofMillis(320), stableAfter = Duration.ofMinutes(1),
        )
        client.start(listOf("KRW-BTC"))
        Thread.sleep(1200)
        client.close()
        assertTrue(attempts.size <= 15, "made ${attempts.size} attempts")
    }

    @Test
    fun `a connection that stayed up resets the backoff`() {
        val attempts = CopyOnWriteArrayList<Long>()
        val listeners = CopyOnWriteArrayList<Pair<WebSocket.Listener, WebSocket>>()
        val connect: (WebSocket.Listener) -> CompletableFuture<WebSocket> = { l ->
            attempts += System.nanoTime()
            val ws = FakeWebSocket()
            l.onOpen(ws)
            if (attempts.size <= 5) l.onClose(ws, 1006, "flap") else listeners += l to ws
            CompletableFuture.completedFuture(ws)
        }
        val client = UpbitStreamClient(
            Silent, Clock.systemUTC(), connect, backoffBase = Duration.ofMillis(20), backoffMax = Duration.ofSeconds(5), idleTimeout = Duration.ofSeconds(30), stableAfter = Duration.ofMillis(50),
        )
        client.start(listOf("KRW-BTC"))
        eventually { attempts.size == 6 }
        Thread.sleep(120)
        val (l, ws) = listeners[0]
        val dropped = System.nanoTime()
        l.onClose(ws, 1006, "gone")
        eventually { attempts.size == 7 }
        val waitedMs = (attempts[6] - dropped) / 1_000_000
        client.close()
        assertTrue(waitedMs < 300, "waited $waitedMs ms")
    }
}
