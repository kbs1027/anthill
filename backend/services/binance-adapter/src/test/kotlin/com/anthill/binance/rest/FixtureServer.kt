package com.anthill.binance.rest

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 바이낸스 REST를 흉내 내는 로컬 서버. 경로별 고정 응답을 돌려주고 받은 요청 URI를 기록한다.
 * [dynamic]이 값을 주면 그것을 먼저 쓴다(요청 쿼리에 따라 응답을 만들 때). [headers]는 n번째 요청의 응답 헤더.
 */
class FixtureServer(
    private val routes: Map<String, String>,
    private val status: (Int) -> Int = { 200 },
    private val headers: (Int) -> Map<String, String> = { emptyMap() },
    private val dynamic: (URI) -> String? = { null },
) : AutoCloseable {
    val requests = CopyOnWriteArrayList<String>()
    val calls = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/v3/") { ex ->
            val n = calls.incrementAndGet()
            requests += ex.requestURI.toString()
            val code = status(n)
            val path = ex.requestURI.path.removePrefix("/api/v3/")
            val body = if (code == 200) dynamic(ex.requestURI) ?: routes[path]?.let(::fixture) ?: "[]" else """{"code":-1003,"msg":"Too many requests"}"""
            headers(n).forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)

    companion object {
        fun fixture(name: String): String =
            requireNotNull(FixtureServer::class.java.getResource("/binance/$name")) { name }.readText()

        /** 요청 쿼리를 이름→값으로(디코딩해서). */
        fun query(uri: URI): Map<String, String> =
            (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = it.split('=', limit = 2)
                k to java.net.URLDecoder.decode(v, Charsets.UTF_8)
            }
    }
}
