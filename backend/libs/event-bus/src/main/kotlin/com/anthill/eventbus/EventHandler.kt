package com.anthill.eventbus

interface EventHandler<T : Any> {
    val type: String
    val payloadType: Class<T>
    fun handle(key: String, payload: T, envelope: EventEnvelope)
}
