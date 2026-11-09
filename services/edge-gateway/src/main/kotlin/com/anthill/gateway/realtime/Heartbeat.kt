package com.anthill.gateway.realtime

import com.anthill.contract.ContractJson
import com.anthill.contract.EventTypes
import com.anthill.contract.FeedStatusEvent
import com.anthill.eventbus.EventEnvelope
import com.anthill.eventbus.EventHandler
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** marketdata 주 서버가 1초마다 보내는 시세 원천 상태를 기억한다. [staleAfter] 넘게 소식이 없으면 시세가 멈춘 것으로 본다. */
class FeedMonitor(private val clock: Clock, private val staleAfter: Duration = Duration.ofMillis(2500)) {
    @Volatile private var connected = false
    @Volatile private var receivedAt: Instant? = null

    fun onStatus(e: FeedStatusEvent) {
        connected = e.connected
        receivedAt = clock.instant()
    }

    fun state(): String {
        val at = receivedAt ?: return "delayed"
        return if (connected && Duration.between(at, clock.instant()) <= staleAfter) "live" else "delayed"
    }
}

class FeedStatusHandler(private val monitor: FeedMonitor) : EventHandler<FeedStatusEvent> {
    override val type = EventTypes.CHART_FEED_STATUS
    override val payloadType = FeedStatusEvent::class.java
    override fun handle(key: String, payload: FeedStatusEvent, envelope: EventEnvelope) = monitor.onStatus(payload)
}

/** 0.5초마다 모든 화면에 생존 신호를 보낸다. 화면은 1.5초 동안 아무 메시지도 없으면 연결이 죽은 것으로 보고 곧바로 다시 붙는다. */
class HeartbeatPublisher(private val handler: StreamWebSocketHandler, private val monitor: FeedMonitor) {
    private val mapper = ContractJson.mapper()

    @Scheduled(fixedRate = 500)
    fun beat() = handler.broadcast(mapper.writeValueAsString(mapOf("type" to "hb", "feed" to monitor.state())))
}
