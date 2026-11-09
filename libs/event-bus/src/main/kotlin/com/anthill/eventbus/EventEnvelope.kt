package com.anthill.eventbus

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/** 이벤트 봉투. Kafka로 옮기면 레코드 값이 되고 [key]가 레코드 키가 된다. */
data class EventEnvelope(
    val eventId: UUID,
    val type: String,
    val version: Int,
    val key: String,
    val occurredAt: Instant,
    val producer: String,
    val payload: JsonNode,
)
