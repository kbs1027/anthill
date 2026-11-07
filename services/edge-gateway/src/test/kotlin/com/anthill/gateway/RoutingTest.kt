package com.anthill.gateway

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutingTest {

    @LocalServerPort var port = 0

    @Test
    fun `api is routed to marketdata`() {
        val res = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/api/candles?market=UPBIT:KRW-BTC&unit=1m&count=2")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, res.statusCode())
        assertEquals(BODY, res.body())
        assertEquals("/api/candles?market=UPBIT:KRW-BTC&unit=1m&count=2", seen.get())
    }

    companion object {
        const val BODY = """{"market":"UPBIT:KRW-BTC","unit":"1m","candles":[],"live":null,"hasMore":false}"""
        private val seen = AtomicReference<String>()
        private val upstream: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen.set("${ex.requestURI.path}?${ex.requestURI.query}")  // 디코딩한 값: 게이트웨이는 `:`를 %3A로 다시 인코딩해 보낸다
                val bytes = BODY.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun marketdata(r: DynamicPropertyRegistry) = r.add("anthill.marketdata.url") { "http://127.0.0.1:${upstream.address.port}" }

        @JvmStatic
        @AfterAll
        fun stop() = upstream.stop(0)
    }
}
