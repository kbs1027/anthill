package com.anthill.gateway.realtime

import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import org.junit.jupiter.api.Test
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
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
}
