package com.anthill.marketdata.ingest

import com.anthill.eventbus.EventEnvelope
import com.anthill.eventbus.EventInbox
import com.anthill.eventbus.InboxResult
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** 비동기 이벤트 수신함. 지금은 HTTP로 받고, Kafka로 바꾸면 같은 핸들러를 컨슈머가 부른다. */
@RestController
class EventsController(private val inbox: EventInbox) {

    @PostMapping("/internal/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun receive(@RequestBody batch: List<EventEnvelope>): InboxResult = inbox.accept(batch)
}
