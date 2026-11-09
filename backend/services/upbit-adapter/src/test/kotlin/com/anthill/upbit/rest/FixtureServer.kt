package com.anthill.upbit.rest

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 업비트 REST를 흉내 내는 로컬 서버. 경로별 고정 응답을 돌려주고 받은 요청 URI를 기록한다. */
class FixtureServer(private val routes: Map<String, String>, private val status: (Int) -> Int = { 200 }) : AutoCloseable {
    val requests = CopyOnWriteArrayList<String>()
    val calls = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/") { ex ->
            val n = calls.incrementAndGet()
            requests += ex.requestURI.toString()
            val code = status(n)
            val path = ex.requestURI.path.removePrefix("/v1/")
            val body = if (code == 200) routes[path]?.let(::fixture) ?: "[]" else """{"error":{"name":"too_many_requests"}}"""
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"
    override fun close() = server.stop(0)

    companion object {
        fun fixture(name: String): String =
            requireNotNull(FixtureServer::class.java.getResource("/upbit/$name")) { name }.readText()
    }
}
