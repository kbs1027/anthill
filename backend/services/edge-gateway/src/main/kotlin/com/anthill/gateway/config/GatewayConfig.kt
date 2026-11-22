package com.anthill.gateway.config

import com.anthill.contract.ContractJson
import com.anthill.eventbus.EventHandler
import com.anthill.eventbus.EventInbox
import com.anthill.eventbus.EventPublisher
import com.anthill.eventbus.HttpEventTransport
import com.anthill.eventbus.Subscriber
import com.anthill.gateway.realtime.CandleUpdateHandler
import com.anthill.gateway.realtime.GatewayFanout
import com.anthill.gateway.realtime.HeartbeatTicker
import com.anthill.gateway.realtime.StreamWebSocketHandler
import com.anthill.gateway.realtime.SubscriptionRegistry
import com.anthill.gateway.realtime.TickerUpdateHandler
import com.anthill.gateway.realtime.ViewingPublisher
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("anthill.events")
data class EventsProperties(val enabled: Boolean = true, val subscribers: List<SubscriberProperties> = emptyList()) {
    data class SubscriberProperties(val types: List<String>, val url: URI)
}

/**
 * `/ws` 설정. 공개 읽기 전용 시세라 인증·쿠키가 없어 출처는 기본으로 모두 허용한다.
 * [heartbeatIdle]: 이만큼 보낸 게 없는 연결에 ping을 보낸다(브라우저는 3초 무소식이면 다시 연결한다).
 */
@ConfigurationProperties("anthill.ws")
data class WsProperties(
    val allowedOrigins: List<String> = listOf("*"),
    val maxSubscriptions: Int = 50,
    val heartbeatIdle: Duration = Duration.ofSeconds(1),
)

@Configuration
@EnableConfigurationProperties(EventsProperties::class, WsProperties::class)
class GatewayConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun eventPublisher(props: EventsProperties, clock: Clock, meter: MeterRegistry): EventPublisher =
        if (!props.enabled) {
            EventPublisher { _, _, _ -> }
        } else {
            HttpEventTransport(
                "edge-gateway", props.subscribers.map { Subscriber(it.types, it.url) }, ContractJson.mapper(), clock,
                onDropped = { meter.counter("anthill.events.dropped").increment(it.toDouble()) },
            )
        }

    @Bean
    fun subscriptionRegistry(ws: WsProperties) = SubscriptionRegistry(ws.maxSubscriptions)

    @Bean
    fun streamWebSocketHandler(registry: SubscriptionRegistry, clock: Clock, ws: WsProperties) =
        StreamWebSocketHandler(registry, clock = clock, heartbeatIdle = ws.heartbeatIdle)

    @Bean
    fun heartbeatTicker(handler: StreamWebSocketHandler) = HeartbeatTicker(handler)

    @Bean
    fun gatewayFanout(registry: SubscriptionRegistry, handler: StreamWebSocketHandler) = GatewayFanout(registry, handler::send)

    @Bean
    fun eventInbox(fanout: GatewayFanout): EventInbox {
        val handlers: List<EventHandler<*>> = listOf(CandleUpdateHandler(fanout), TickerUpdateHandler(fanout))
        return EventInbox(handlers, ContractJson.mapper())
    }

    @Bean
    fun viewingPublisher(registry: SubscriptionRegistry, publisher: EventPublisher, clock: Clock) =
        ViewingPublisher(registry, publisher, clock = clock)
}
