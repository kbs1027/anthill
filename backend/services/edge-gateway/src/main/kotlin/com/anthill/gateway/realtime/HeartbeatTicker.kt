package com.anthill.gateway.realtime

import org.springframework.scheduling.annotation.Scheduled

/**
 * 250ms마다 [StreamWebSocketHandler.heartbeat]를 부른다. 1초 동안 보낸 게 없는 연결에만 ping이 나간다.
 * 1초마다 확인하면 정상일 때도 메시지 사이 공백이 2초까지 벌어져 브라우저의 3초 판정에 너무 가깝다.
 * 250ms면 공백이 최대 약 1.25초라 3초 판정까지 여유가 있다.
 */
class HeartbeatTicker(private val handler: StreamWebSocketHandler) {
    @Scheduled(fixedRate = 250)
    fun tick() = handler.heartbeat()
}
