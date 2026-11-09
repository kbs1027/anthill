package com.anthill.eventbus

/** 도메인 코드는 이 인터페이스로만 이벤트를 보낸다. 전송 방식(지금 HTTP, 나중 Kafka)은 구현체가 정한다. */
fun interface EventPublisher {
    fun publish(type: String, key: String, payload: Any)
}
