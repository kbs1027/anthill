package com.anthill.gateway.config

import com.anthill.gateway.realtime.StreamWebSocketHandler
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(private val handler: StreamWebSocketHandler, private val ws: WsProperties) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/ws").setAllowedOriginPatterns(*ws.allowedOrigins.toTypedArray())
    }
}
