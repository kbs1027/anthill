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
import java.util.logging.Level
import java.util.logging.Logger

/**
 * `/ws`. 클라이언트 → {"op":"subscribe"|"unsubscribe","market":"UPBIT:KRW-BTC","unit":"1m"} | {"op":"subscribeTickers"}.
 * 잘못된 요청에는 {"type":"error","message":…}를 보내고 연결은 유지한다.
 * 보내기는 세션별 [ConcurrentWebSocketSessionDecorator]: 느린 클라이언트가 한도를 넘기면 그 연결만 끊긴다(재연결 후 다시 받는다).
 */
class StreamWebSocketHandler(
    private val registry: SubscriptionRegistry,
    private val sendTimeLimitMs: Int = 5_000,
    private val bufferSizeLimit: Int = 1 shl 20,
) : TextWebSocketHandler() {
    private val log = Logger.getLogger(StreamWebSocketHandler::class.java.name)
    private val mapper = ContractJson.mapper()
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        sessions[session.id] = ConcurrentWebSocketSessionDecorator(session, sendTimeLimitMs, bufferSizeLimit)
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

    fun send(sessionId: String, json: String) {
        val s = sessions[sessionId] ?: return
        try {
            s.sendMessage(TextMessage(json))
        } catch (e: Exception) {
            log.log(Level.FINE, "closing slow or broken session $sessionId", e)
            forget(sessionId)
            runCatching { s.close(CloseStatus.SESSION_NOT_RELIABLE) }
        }
    }

    private fun forget(sessionId: String) {
        sessions.remove(sessionId)
        registry.remove(sessionId)
    }
}
