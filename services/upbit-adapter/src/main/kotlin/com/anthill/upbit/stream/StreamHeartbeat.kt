package com.anthill.upbit.stream

import com.anthill.contract.EventTypes
import com.anthill.contract.StreamStatusEvent
import com.anthill.eventbus.EventPublisher
import com.anthill.upbit.rest.UpbitJson
import java.time.Clock

/**
 * 스트림 상태를 주기적으로 다시 알린다(연결이면 at = 연결 시각, 끊김이면 지금).
 * 어댑터 프로세스가 죽으면 끊김 이벤트를 보낼 수 없으므로, marketdata는 이 알림이 끊기는 것으로 알아챈다.
 */
class StreamHeartbeat(private val stream: StreamControl, private val publisher: EventPublisher, private val clock: Clock) {
    fun beat() {
        val s = stream.status
        publisher.publish(EventTypes.MARKET_STREAM_STATUS, UpbitJson.SOURCE, StreamStatusEvent(UpbitJson.SOURCE, s.connected, s.since ?: clock.instant()))
    }
}
