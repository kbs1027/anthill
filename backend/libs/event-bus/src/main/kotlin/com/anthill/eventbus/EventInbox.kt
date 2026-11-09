package com.anthill.eventbus

import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

data class InboxResult(val handled: Int, val duplicates: Int, val unknown: Int, val failed: Int)

/**
 * 받은 이벤트 묶음을 순서대로 핸들러에 넘긴다. 최근 [remember]개의 eventId를 기억해 같은 이벤트를 두 번 처리하지 않는다.
 * 핸들러가 실패한 이벤트는 기억하지 않아서, 다시 오면 다시 처리한다.
 */
class EventInbox(handlers: List<EventHandler<*>>, private val mapper: JsonMapper, private val remember: Int = 200_000) {

    private val log = Logger.getLogger(EventInbox::class.java.name)
    private val byType = handlers.associateBy { it.type }
    private val seen = object : LinkedHashMap<UUID, Boolean>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Boolean>) = size > remember
    }

    fun accept(batch: List<EventEnvelope>): InboxResult {
        var handled = 0
        var duplicates = 0
        var unknown = 0
        var failed = 0
        for (envelope in batch) {
            if (synchronized(seen) { seen.containsKey(envelope.eventId) }) {
                duplicates++
                continue
            }
            val handler = byType[envelope.type]
            if (handler == null) {
                unknown++
                remember(envelope.eventId)
                continue
            }
            try {
                dispatch(handler, envelope)
                remember(envelope.eventId)
                handled++
            } catch (e: Exception) {
                failed++
                log.log(Level.WARNING, "handler failed for ${envelope.type} ${envelope.eventId}", e)
            }
        }
        return InboxResult(handled, duplicates, unknown, failed)
    }

    private fun <T : Any> dispatch(handler: EventHandler<T>, envelope: EventEnvelope) {
        handler.handle(envelope.key, mapper.treeToValue(envelope.payload, handler.payloadType), envelope)
    }

    private fun remember(id: UUID) = synchronized(seen) { seen[id] = true }
}
