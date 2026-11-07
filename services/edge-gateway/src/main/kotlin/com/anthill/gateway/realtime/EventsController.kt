package com.anthill.gateway.realtime

import com.anthill.contract.CandleUpdateEvent
import com.anthill.contract.EventTypes
import com.anthill.contract.TickerSnapshot
import com.anthill.eventbus.EventEnvelope
import com.anthill.eventbus.EventHandler
import com.anthill.eventbus.EventInbox
import com.anthill.eventbus.InboxResult
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

class CandleUpdateHandler(private val fanout: GatewayFanout) : EventHandler<CandleUpdateEvent> {
    override val type = EventTypes.CHART_CANDLE_UPDATE
    override val payloadType = CandleUpdateEvent::class.java
    override fun handle(key: String, payload: CandleUpdateEvent, envelope: EventEnvelope) = fanout.onCandle(payload)
}

class TickerUpdateHandler(private val fanout: GatewayFanout) : EventHandler<TickerSnapshot> {
    override val type = EventTypes.CHART_TICKER_UPDATE
    override val payloadType = TickerSnapshot::class.java
    override fun handle(key: String, payload: TickerSnapshot, envelope: EventEnvelope) = fanout.onTicker(payload)
}

/** marketdata가 1초마다 보내는 `chart.*` 묶음을 받는다. 한 묶음은 세션마다 메시지 하나로 나간다. */
@RestController
class EventsController(private val inbox: EventInbox, private val fanout: GatewayFanout) {

    @PostMapping("/internal/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun receive(@RequestBody batch: List<EventEnvelope>): InboxResult = fanout.batch { inbox.accept(batch) }
}
