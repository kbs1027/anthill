package com.anthill.eventbus

import com.anthill.contract.ContractJson
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class EventInboxTest {

    private val mapper = ContractJson.mapper()

    data class Ping(val n: Int)

    private class Recorder(override val type: String, private val failOn: Int? = null) : EventHandler<Ping> {
        val seen = mutableListOf<Int>()
        override val payloadType = Ping::class.java
        override fun handle(key: String, payload: Ping, envelope: EventEnvelope) {
            if (payload.n == failOn) error("boom")
            seen += payload.n
        }
    }

    private fun env(type: String, n: Int, id: UUID = UUID.randomUUID()) =
        EventEnvelope(id, type, 1, "k$n", Instant.parse("2026-11-08T00:00:00Z"), "test", mapper.valueToTree(Ping(n)))

    @Test
    fun `inbox skips duplicate eventId`() {
        val h = Recorder("ping.v1")
        val inbox = EventInbox(listOf(h), mapper)
        val e = env("ping.v1", 1)
        assertEquals(InboxResult(1, 0, 0, 0), inbox.accept(listOf(e)))
        assertEquals(InboxResult(0, 1, 0, 0), inbox.accept(listOf(e)))
        assertEquals(listOf(1), h.seen)
    }

    @Test
    fun `inbox counts unknown type and continues`() {
        val h = Recorder("ping.v1")
        val result = EventInbox(listOf(h), mapper).accept(listOf(env("other.v1", 1), env("ping.v1", 2)))
        assertEquals(InboxResult(1, 0, 1, 0), result)
        assertEquals(listOf(2), h.seen)
    }

    @Test
    fun `handler failure does not stop batch`() {
        val h = Recorder("ping.v1", failOn = 2)
        val result = EventInbox(listOf(h), mapper).accept((1..3).map { env("ping.v1", it) })
        assertEquals(InboxResult(2, 0, 0, 1), result)
        assertEquals(listOf(1, 3), h.seen)
    }

    @Test
    fun `failed event is processed again when redelivered`() {
        var fail = true
        val h = object : EventHandler<Ping> {
            val seen = mutableListOf<Int>()
            override val type = "ping.v1"
            override val payloadType = Ping::class.java
            override fun handle(key: String, payload: Ping, envelope: EventEnvelope) {
                if (fail) error("boom")
                seen += payload.n
            }
        }
        val inbox = EventInbox(listOf(h), mapper)
        val e = env("ping.v1", 7)
        inbox.accept(listOf(e))
        fail = false
        assertEquals(InboxResult(1, 0, 0, 0), inbox.accept(listOf(e)))
        assertEquals(listOf(7), h.seen)
    }
}
