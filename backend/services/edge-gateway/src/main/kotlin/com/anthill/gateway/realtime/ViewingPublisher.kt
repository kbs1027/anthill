package com.anthill.gateway.realtime

import com.anthill.contract.EventTypes
import com.anthill.contract.ViewingEvent
import com.anthill.eventbus.EventPublisher
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.util.UUID

/** 10초마다 이 게이트웨이에서 차트로 보고 있는 종목을 알린다. marketdata가 정합성 검사 순서를 정할 때 쓴다(빈 목록도 보낸다). */
class ViewingPublisher(
    private val registry: SubscriptionRegistry,
    private val publisher: EventPublisher,
    private val gatewayId: String = UUID.randomUUID().toString(),
    private val clock: Clock = Clock.systemUTC(),
) {
    @Scheduled(fixedRate = 10_000)
    fun publish() = publisher.publish(EventTypes.REALTIME_VIEWING, gatewayId, ViewingEvent(gatewayId, registry.viewed(), clock.instant()))
}
