package com.anthill.marketdata.source

import com.anthill.contract.ContractJson
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpSourceClientFailoverTest {

    private class Server(status: Int) : AutoCloseable {
        val calls = AtomicInteger()
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { x ->
                calls.incrementAndGet()
                val body = "[]".toByteArray()
                x.sendResponseHeaders(status, body.size.toLong())
                x.responseBody.use { it.write(body) }
            }
            start()
        }
        val url: URI = URI("http://127.0.0.1:${http.address.port}")
        override fun close() = http.stop(0)
    }

    private val dead = URI("http://127.0.0.1:1")

    @Test
    fun `a dead adapter falls through to the other one and stays there`() {
        Server(200).use { b ->
            val client = HttpSourceClient(mapOf("UPBIT" to listOf(dead, b.url)), ContractJson.mapper())
            assertEquals(emptyList(), client.instruments("UPBIT"))
            assertEquals(emptyList(), client.instruments("UPBIT"))
            assertEquals(2, b.calls.get())
        }
    }

    @Test
    fun `a server error falls through`() {
        Server(503).use { a ->
            Server(200).use { b ->
                val client = HttpSourceClient(mapOf("UPBIT" to listOf(a.url, b.url)), ContractJson.mapper())
                assertEquals(emptyList(), client.tickers("UPBIT"))
                assertEquals(1, b.calls.get())
            }
        }
    }

    @Test
    fun `a client error does not hide behind the other adapter`() {
        Server(400).use { a ->
            Server(200).use { b ->
                val client = HttpSourceClient(mapOf("UPBIT" to listOf(a.url, b.url)), ContractJson.mapper())
                assertFailsWith<SourceUnavailableException> { client.tickers("UPBIT") }
                assertEquals(0, b.calls.get())
            }
        }
    }

    @Test
    fun `every adapter down reports the failure`() {
        val client = HttpSourceClient(mapOf("UPBIT" to listOf(dead)), ContractJson.mapper())
        assertFailsWith<Exception> { client.tickers("UPBIT") }
    }
}
