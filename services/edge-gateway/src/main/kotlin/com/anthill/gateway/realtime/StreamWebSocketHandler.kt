package com.anthill.gateway.realtime

import com.anthill.contract.CandleUnit
import com.anthill.contract.ContractJson
import com.anthill.contract.InstrumentId
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * `/ws`. 클라이언트 → {"op":"subscribe"|"unsubscribe","market":"UPBIT:KRW-BTC","unit":"1m"} | {"op":"subscribeTickers"}.
 * 잘못된 요청에는 {"type":"error","message":…}를 보내고 연결은 유지한다.
 * 보내기는 세션마다 자기 대기열과 가상 스레드에서 한다. 쓰기가 막힌 클라이언트(잠든 노트북, 가득 찬 TCP 버퍼)가
 * 보내는 쪽(marketdata 이벤트 처리)과 다른 세션을 붙잡지 않는다. 밀린 메시지가 [maxPending]을 넘으면 그 연결을 끊는다
 * (메시지를 버리면 마감·정정이 빠진 차트가 남으므로 끊고, 클라이언트는 다시 붙어 최신 구간을 새로 받는다).
 */
class StreamWebSocketHandler(
    private val registry: SubscriptionRegistry,
    private val sendTimeLimitMs: Int = 5_000,
    private val bufferSizeLimit: Int = 1 shl 20,
    private val maxPending: Int = 64,
) : TextWebSocketHandler() {

    private class Outbox(val session: WebSocketSession, val executor: ExecutorService) {
        val pending = AtomicInteger()
    }

    private val log = Logger.getLogger(StreamWebSocketHandler::class.java.name)
    private val mapper = ContractJson.mapper()
    private val sessions = ConcurrentHashMap<String, Outbox>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        sessions[session.id] = Outbox(
            ConcurrentWebSocketSessionDecorator(session, sendTimeLimitMs, bufferSizeLimit),
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ws-send-${session.id}").factory()),
        )
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        try {
            val req = mapper.readTree(message.payload)
            when (val op = req.path("op").asString("")) {
                "subscribe", "unsubscribe" -> {
                    val market = InstrumentId.parse(req.path("market").asString(""))
                    val code = req.path("unit").asString("")
                    val unit = requireNotNull(CandleUnit.fromCode(code)) { "unknown unit $code" }
                    if (op == "subscribe") registry.subscribe(session.id, market, unit) else registry.unsubscribe(session.id, market, unit)
                }
                "subscribeTickers" -> registry.subscribeTickers(session.id)
                else -> throw IllegalArgumentException("unknown op '$op'")
            }
        } catch (e: Exception) {
            send(session.id, mapper.writeValueAsString(mapOf("type" to "error", "message" to (e.message ?: "bad request"))))
        }
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        forget(session.id)
        if (session.isOpen) session.close(CloseStatus.SERVER_ERROR)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) = forget(session.id)

    /** 막히지 않는다: 세션 대기열에 넣고 바로 돌아온다. */
    fun send(sessionId: String, json: String) {
        val out = sessions[sessionId] ?: return
        if (out.pending.incrementAndGet() > maxPending) {
            log.fine("session $sessionId is $maxPending messages behind, closing")
            drop(sessionId, out)
            return
        }
        try {
            out.executor.execute {
                try {
                    out.session.sendMessage(TextMessage(json))
                } catch (e: Exception) {
                    log.log(Level.FINE, "closing broken session $sessionId", e)
                    drop(sessionId, out)
                } finally {
                    out.pending.decrementAndGet()
                }
            }
        } catch (_: RejectedExecutionException) {
            // 이미 닫는 중
        }
    }

    private fun drop(sessionId: String, out: Outbox) {
        forget(sessionId)
        // 막힌 연결은 닫기도 막힐 수 있어 따로 닫는다.
        Thread.ofVirtual().start { runCatching { out.session.close(CloseStatus.SESSION_NOT_RELIABLE) } }
    }

    private fun forget(sessionId: String) {
        sessions.remove(sessionId)?.executor?.shutdownNow()
        registry.remove(sessionId)
    }
}
