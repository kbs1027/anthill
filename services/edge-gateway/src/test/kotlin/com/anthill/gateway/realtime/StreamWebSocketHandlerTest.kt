package com.anthill.gateway.realtime

import com.anthill.contract.Candle
import com.anthill.contract.CandleState
import com.anthill.contract.CandleUnit
import com.anthill.contract.CandleUpdateEvent
import com.anthill.contract.ContractJson
import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentId
import com.anthill.contract.TickerSnapshot
import com.anthill.eventbus.EventEnvelope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamWebSocketHandlerTest {

    @LocalServerPort var port = 0
    @Autowired lateinit var registry: SubscriptionRegistry

    private val mapper = ContractJson.mapper()
    private val http = HttpClient.newHttpClient()
    private val open = ArrayList<WebSocketSession>()
    // 테스트마다 다른 종목: 앞 테스트의 세션이 레지스트리에서 빠지기 전이라도 서로 섞이지 않는다.
    private val btc = InstrumentId.parse("UPBIT:KRW-T" + UUID.randomUUID().toString().take(8).uppercase())
    private val start = Instant.parse("2026-11-08T10:20:00Z")

    @AfterEach
    fun closeAll() = open.forEach { it.close() }

    private inner class Client {
        val received = LinkedBlockingQueue<String>()
        val session: WebSocketSession = StandardWebSocketClient().execute(
            object : TextWebSocketHandler() {
                override fun handleTextMessage(session: WebSocketSession, message: TextMessage) { received += message.payload }
            },
            "ws://localhost:$port/ws",
        ).get(5, TimeUnit.SECONDS).also { open += it }

        fun send(json: String) = session.sendMessage(TextMessage(json))
        fun subscribe(unit: String) = send("""{"op":"subscribe","market":"${btc.value}","unit":"$unit"}""")
        fun unsubscribe(unit: String) = send("""{"op":"unsubscribe","market":"${btc.value}","unit":"$unit"}""")

        /** 서버는 한 세션의 메시지를 차례로 처리한다: 모르는 op에 대한 오류 응답이 오면 앞서 보낸 요청은 처리된 것이다. */
        fun barrier() {
            send("""{"op":"barrier"}""")
            assertEquals("error", mapper.readTree(assertNotNull(next()))["type"].asString())
        }

        fun next(): String? = received.poll(3, TimeUnit.SECONDS)
        fun nothingWithin(ms: Long): String? = received.poll(ms, TimeUnit.MILLISECONDS)
    }

    private fun candle(start: Instant, close: String = "100") = Candle(
        btc, CandleUnit.M1, start, BigDecimal("100"), BigDecimal("101"), BigDecimal("99"), BigDecimal(close), BigDecimal("1.5"), BigDecimal("150"),
    )

    private fun envelope(type: String, payload: Any) = EventEnvelope(
        UUID.randomUUID(), type, 1, btc.value, Instant.now(), "marketdata-service", mapper.valueToTree(payload),
    )

    private fun post(vararg events: EventEnvelope) {
        val res = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/internal/events"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(events.toList()))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(202, res.statusCode(), res.body())
    }

    private fun await(check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!check()) {
            check(System.nanoTime() < until) { "condition not met in 3s" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `subscribed session receives candle after event post`() {
        val c = Client()
        c.subscribe("1m")
        c.barrier()
        post(envelope(EventTypes.CHART_CANDLE_UPDATE, CandleUpdateEvent(btc, CandleUnit.M1, start, candle(start), CandleState.LIVE)))
        val msg = mapper.readTree(assertNotNull(c.next()))
        assertEquals("candles", msg["type"].asString())
        assertEquals(1, msg["items"].size())
        val item = msg["items"][0]
        assertEquals(btc.value, item["market"].asString())
        assertEquals("1m", item["unit"].asString())
        assertEquals("live", item["state"].asString())
        assertEquals("2026-11-08T10:20:00Z", item["time"].asString())
        assertEquals("100", item["candle"]["close"].asString())
    }

    @Test
    fun `unsubscribed session receives nothing`() {
        val c = Client()
        c.subscribe("1m")
        c.unsubscribe("1m")
        c.barrier()
        assertEquals(emptySet(), registry.sessionsFor(btc, CandleUnit.M1))
        val other = Client()
        other.subscribe("5m")
        other.barrier()
        post(envelope(EventTypes.CHART_CANDLE_UPDATE, CandleUpdateEvent(btc, CandleUnit.M1, start, candle(start), CandleState.LIVE)))
        assertNull(c.nothingWithin(500))
        assertNull(other.nothingWithin(100))
    }

    @Test
    fun `one message per session per batch`() {
        val c = Client()
        c.subscribe("1m")
        c.barrier()
        val next = start.plusSeconds(60)
        post(
            envelope(EventTypes.CHART_CANDLE_UPDATE, CandleUpdateEvent(btc, CandleUnit.M1, start, candle(start, "101"), CandleState.CLOSED)),
            envelope(EventTypes.CHART_CANDLE_UPDATE, CandleUpdateEvent(btc, CandleUnit.M1, next, candle(next, "102"), CandleState.LIVE)),
            envelope(EventTypes.CHART_CANDLE_UPDATE, CandleUpdateEvent(btc, CandleUnit.M1, start.minusSeconds(60), null, CandleState.REMOVED)),
        )
        val msg = mapper.readTree(assertNotNull(c.next()))
        assertEquals(3, msg["items"].size())
        assertEquals(listOf("closed", "live", "removed"), (0 until 3).map { msg["items"][it]["state"].asString() })
        assertEquals(true, msg["items"][2]["candle"].isNull)
        assertNull(c.nothingWithin(300))
    }

    @Test
    fun `ticker subscribers get tickers`() {
        val c = Client()
        val plain = Client()
        c.send("""{"op":"subscribeTickers"}""")
        c.barrier()
        post(envelope(EventTypes.CHART_TICKER_UPDATE, TickerSnapshot(btc, BigDecimal("101"), BigDecimal("0.0123"), BigDecimal("123456789.5"), candle(start))))
        val msg = mapper.readTree(assertNotNull(c.next()))
        assertEquals("tickers", msg["type"].asString())
        val item = msg["items"][0]
        assertEquals(btc.value, item["market"].asString())
        assertEquals("101", item["price"].asString())
        assertEquals("0.0123", item["changeRate"].asString())
        assertEquals("123456789.5", item["amount24h"].asString())
        assertNull(plain.nothingWithin(300))
    }

    @Test
    fun `closed session is forgotten`() {
        val c = Client()
        c.subscribe("1m")
        c.barrier()
        assertEquals(1, registry.sessionsFor(btc, CandleUnit.M1).size)
        c.session.close()
        await { registry.sessionsFor(btc, CandleUnit.M1).isEmpty() }
        assertEquals(emptySet(), registry.viewed().filter { it == btc }.toSet())
    }

    @Test
    fun `bad request gets an error message and keeps the session`() {
        val c = Client()
        c.send("""{"op":"subscribe","market":"nope","unit":"7m"}""")
        assertEquals("error", mapper.readTree(assertNotNull(c.next()))["type"].asString())
        c.subscribe("1m")
        c.barrier()
        assertEquals(1, registry.sessionsFor(btc, CandleUnit.M1).size)
    }
}
