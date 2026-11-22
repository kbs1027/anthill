package com.anthill.gateway.realtime

import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import org.junit.jupiter.api.Test
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 리뷰 M5: 쓰기가 막힌 클라이언트 하나(잠든 노트북, 가득 찬 TCP 버퍼)가 다른 모든 세션과 marketdata 전송을 붙잡았다. */
class StreamWebSocketHandlerSendTest {

    private fun session(id: String, closed: AtomicBoolean = AtomicBoolean(), onSend: (String) -> Unit): WebSocketSession =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WebSocketSession::class.java)) { proxy, m, args ->
            when (m.name) {
                "getId" -> id
                "isOpen" -> !closed.get()
                "sendMessage" -> { onSend((args[0] as TextMessage).payload); null }
                "close" -> { closed.set(true); null }
                "getTextMessageSizeLimit", "getBinaryMessageSizeLimit" -> 65536
                "hashCode" -> id.hashCode()
                "equals" -> proxy === args[0]
                "toString" -> "fake-$id"
                else -> null
            }
        } as WebSocketSession

    @Test
    fun `a blocked client does not hold up the sender or other clients`() {
        val handler = StreamWebSocketHandler(SubscriptionRegistry())
        val release = CountDownLatch(1)
        val fast = LinkedBlockingQueue<String>()
        handler.afterConnectionEstablished(session("slow") { release.await(3, TimeUnit.SECONDS) })
        handler.afterConnectionEstablished(session("fast") { fast += it })
        val sender = CompletableFuture.runAsync {
            handler.send("slow", "a")
            handler.send("fast", "b")
        }
        assertEquals("b", fast.poll(2, TimeUnit.SECONDS))
        sender.get(1, TimeUnit.SECONDS)
        release.countDown()
    }

    @Test
    fun `a client that cannot keep up is dropped instead of queueing forever`() {
        val registry = SubscriptionRegistry()
        val handler = StreamWebSocketHandler(registry)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        handler.afterConnectionEstablished(session("slow", closed) { release.await(3, TimeUnit.SECONDS) })
        registry.subscribe("slow", InstrumentId.parse("UPBIT:KRW-BTC"), CandleUnit.M1)
        CompletableFuture.runAsync { repeat(200) { handler.send("slow", "m$it") } }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!closed.get() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue(closed.get(), "slow session not closed")
        assertEquals(emptySet(), registry.sessionsFor(InstrumentId.parse("UPBIT:KRW-BTC"), CandleUnit.M1))
        release.countDown()
    }

    // ── 하트비트: 1초 동안 보낸 게 없는 연결에만 ping ──

    /** 테스트가 직접 시각을 옮기는 시계. */
    private class MutableClock(var now: Instant = Instant.parse("2026-11-22T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
        fun advanceMillis(ms: Long) { now = now.plusMillis(ms) }
    }

    /** 보내기는 세션별 스레드에서 비동기로 일어나므로, 안 왔음을 확인할 때는 잠깐 기다려 본다. */
    private fun LinkedBlockingQueue<String>.nothing() = assertNull(poll(200, TimeUnit.MILLISECONDS))
    private fun LinkedBlockingQueue<String>.next() = poll(2, TimeUnit.SECONDS)

    @Test
    fun `1초 동안 보낸 게 없는 세션에만 ping을 보낸다`() {
        val clock = MutableClock()
        val handler = StreamWebSocketHandler(SubscriptionRegistry(), clock = clock)
        val quiet = LinkedBlockingQueue<String>()
        val busy = LinkedBlockingQueue<String>()
        handler.afterConnectionEstablished(session("quiet") { quiet += it })
        handler.afterConnectionEstablished(session("busy") { busy += it })

        clock.advanceMillis(999)
        handler.heartbeat()
        quiet.nothing() // 0.999초: 아직 아니다
        handler.send("busy", "data")

        clock.advanceMillis(1)
        handler.heartbeat()
        assertEquals(StreamWebSocketHandler.PING, quiet.next()) // 연결 뒤 1초 동안 보낸 게 없다
        assertEquals("data", busy.next())
        busy.nothing() // 방금 데이터를 보낸 세션에는 ping이 없다
    }

    @Test
    fun `데이터를 보낸 세션도 1초가 지나면 ping을 받고, ping을 보낸 뒤에는 다시 1초를 센다`() {
        val clock = MutableClock()
        val handler = StreamWebSocketHandler(SubscriptionRegistry(), clock = clock)
        val got = LinkedBlockingQueue<String>()
        handler.afterConnectionEstablished(session("s") { got += it })
        handler.send("s", "data")
        assertEquals("data", got.next())

        clock.advanceMillis(500)
        handler.heartbeat()
        got.nothing()

        clock.advanceMillis(500)
        handler.heartbeat()
        assertEquals(StreamWebSocketHandler.PING, got.next())

        clock.advanceMillis(999)
        handler.heartbeat()
        got.nothing() // ping도 보낸 것으로 친다

        clock.advanceMillis(1)
        handler.heartbeat()
        assertEquals(StreamWebSocketHandler.PING, got.next())
    }

    @Test
    fun `쓰기가 막힌 조용한 세션은 쌓인 ping이 한도를 넘으면 끊기고 구독에서 빠진다`() {
        val clock = MutableClock()
        val registry = SubscriptionRegistry()
        val handler = StreamWebSocketHandler(registry, maxPending = 2, clock = clock)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        handler.afterConnectionEstablished(session("stuck", closed) { release.await(3, TimeUnit.SECONDS) })
        registry.subscribe("stuck", InstrumentId.parse("UPBIT:KRW-BTC"), CandleUnit.M1)

        repeat(3) {
            clock.advanceMillis(1000)
            handler.heartbeat()
        }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!closed.get() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue(closed.get(), "막힌 세션이 끊기지 않았다")
        assertEquals(emptySet(), registry.sessionsFor(InstrumentId.parse("UPBIT:KRW-BTC"), CandleUnit.M1))
        release.countDown()
    }

    @Test
    fun `닫힌 세션에는 ping을 보내지 않는다`() {
        val clock = MutableClock()
        val handler = StreamWebSocketHandler(SubscriptionRegistry(), clock = clock)
        val got = LinkedBlockingQueue<String>()
        val s = session("gone") { got += it }
        handler.afterConnectionEstablished(s)
        handler.afterConnectionClosed(s, CloseStatus.NORMAL)

        clock.advanceMillis(5000)
        handler.heartbeat()
        got.nothing()
    }
}
