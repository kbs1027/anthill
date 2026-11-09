package com.anthill.upbit.stream

import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** 업비트 웹소켓 자리를 대신하는 가짜. 보낸 텍스트를 기록하고, 닫히면 표시한다. */
class FakeWebSocket : WebSocket {
    val sent = CopyOnWriteArrayList<String>()
    @Volatile var aborted = false
    override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> { sent += data.toString(); return CompletableFuture.completedFuture(this) }
    override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
    override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
    override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)
    override fun sendClose(statusCode: Int, reason: String): CompletableFuture<WebSocket> { aborted = true; return CompletableFuture.completedFuture(this) }
    override fun request(n: Long) {}
    override fun getSubprotocol() = ""
    override fun isOutputClosed() = aborted
    override fun isInputClosed() = aborted
    override fun abort() { aborted = true }
}

/** connect 함수 대역: 호출될 때마다 새 가짜 소켓을 만들고 리스너를 붙잡아 둔다. */
class FakeConnector {
    val sockets = CopyOnWriteArrayList<FakeWebSocket>()
    val listeners = CopyOnWriteArrayList<WebSocket.Listener>()
    fun connect(listener: WebSocket.Listener): CompletableFuture<WebSocket> {
        val ws = FakeWebSocket()
        sockets += ws
        listeners += listener
        listener.onOpen(ws)
        return CompletableFuture.completedFuture(ws)
    }
}
