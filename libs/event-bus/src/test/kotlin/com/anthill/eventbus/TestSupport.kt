package com.anthill.eventbus

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 받은 요청 본문을 기록하는 로컬 HTTP 서버. [status]가 요청 번호(1부터)별 응답 코드를 정한다. */
class RecordingServer(private val status: (Int) -> Int = { 202 }, private val delayMs: (Int) -> Long = { 0 }) : AutoCloseable {
    val bodies = CopyOnWriteArrayList<String>()
    val okBodies = CopyOnWriteArrayList<String>()
    val calls = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/internal/events") { ex ->
            val n = calls.incrementAndGet()
            val body = ex.requestBody.readAllBytes().decodeToString()
            bodies += body
            Thread.sleep(delayMs(n))
            val code = status(n)
            if (code in 200..299) okBodies += body
            ex.sendResponseHeaders(code, -1)
            ex.close()
        }
        start()
    }
    val url: URI get() = URI("http://127.0.0.1:${server.address.port}/internal/events")
    override fun close() = server.stop(0)
}

fun eventually(timeoutMs: Long = 3000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return
        Thread.sleep(10)
    }
    check(cond()) { "condition not met within ${timeoutMs}ms" }
}
