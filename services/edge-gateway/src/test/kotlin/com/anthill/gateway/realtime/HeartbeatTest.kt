package com.anthill.gateway.realtime

import com.anthill.contract.ContractJson
import com.anthill.contract.FeedStatusEvent
import org.junit.jupiter.api.Test
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/** 화면이 끊김을 1~2초 안에 알아채려면 데이터가 없어도 매초 무언가 와야 한다. 게이트웨이는 살아 있어도 시세가 멈췄을 수 있으니 그것도 함께 알린다. */
class HeartbeatTest {

    private var now = Instant.parse("2026-11-08T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(z: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
    private val mapper = ContractJson.mapper()

    private fun session(id: String, onSend: (String) -> Unit): WebSocketSession =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WebSocketSession::class.java)) { proxy, m, args ->
            when (m.name) {
                "getId" -> id
                "isOpen" -> true
                "sendMessage" -> { onSend((args[0] as TextMessage).payload); null }
                "getTextMessageSizeLimit", "getBinaryMessageSizeLimit" -> 65536
                "hashCode" -> id.hashCode()
                "equals" -> proxy === args[0]
                "toString" -> "fake-$id"
                else -> null
            }
        } as WebSocketSession

    @Test
    fun `feed is delayed until a status arrives`() {
        assertEquals("delayed", FeedMonitor(clock).state())
    }

    @Test
    fun `feed is live while connected statuses keep arriving`() {
        val monitor = FeedMonitor(clock, Duration.ofMillis(2500))
        monitor.onStatus(FeedStatusEvent(true, now))
        now = now.plusMillis(2400)
        assertEquals("live", monitor.state())
    }

    @Test
    fun `feed is delayed when marketdata goes quiet`() {
        val monitor = FeedMonitor(clock, Duration.ofMillis(2500))
        monitor.onStatus(FeedStatusEvent(true, now))
        now = now.plusMillis(2600)
        assertEquals("delayed", monitor.state())
    }

    @Test
    fun `feed is delayed when the source is disconnected`() {
        val monitor = FeedMonitor(clock)
        monitor.onStatus(FeedStatusEvent(false, now))
        assertEquals("delayed", monitor.state())
    }

    @Test
    fun `every open session gets the heartbeat with the feed state`() {
        val handler = StreamWebSocketHandler(SubscriptionRegistry())
        val a = LinkedBlockingQueue<String>()
        val b = LinkedBlockingQueue<String>()
        handler.afterConnectionEstablished(session("a") { a += it })
        handler.afterConnectionEstablished(session("b") { b += it })
        val monitor = FeedMonitor(clock).also { it.onStatus(FeedStatusEvent(true, now)) }
        HeartbeatPublisher(handler, monitor).beat()
        for (q in listOf(a, b)) {
            val json = mapper.readTree(q.poll(2, TimeUnit.SECONDS))
            assertEquals("hb", json["type"].asString())
            assertEquals("live", json["feed"].asString())
        }
    }
}
