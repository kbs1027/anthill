package com.anthill.binance.stream

import com.anthill.contract.StreamStatus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

interface StreamListener {
    fun onMessage(m: BinanceKlineMsg)
    fun onConnected(at: Instant)
    fun onDisconnected(at: Instant)
}

/** 다른 컴포넌트가 스트림을 다루는 창구. */
interface StreamControl {
    fun resubscribe(symbols: List<String>)
    fun pause(d: Duration)
    val status: StreamStatus
}

/**
 * 바이낸스 웹소켓 하나로 USDT 전 종목의 `<symbol>@kline_1s`를 구독한다. 끊기면 [backoffBase]부터 두 배씩([backoffMax]까지)
 * 기다렸다 다시 연결하고, 연결·끊김은 [StreamListener]로 알린다. 바이낸스가 24시간마다 끊는 것도 같은 방식으로 다시 붙는다.
 * 바이낸스는 연결마다 들어오는 메시지를 초당 5개로 막으므로 구독 메시지는 [sendInterval] 간격으로 하나씩 보낸다.
 * 오류 없이 멈춘 연결은 [idleTimeout] 동안 프레임이 없으면 끊고 다시 연결한다(정상이면 종목마다 1초에 하나씩 온다).
 * 바이낸스의 Ping에는 JDK WebSocket이 Pong을 알아서 보낸다.
 */
class BinanceStreamClient(
    private val listener: StreamListener,
    private val clock: Clock,
    private val connect: (WebSocket.Listener) -> CompletableFuture<WebSocket>,
    private val backoffBase: Duration = Duration.ofSeconds(1),
    private val backoffMax: Duration = Duration.ofSeconds(30),
    private val idleTimeout: Duration = Duration.ofSeconds(15),
    private val sendInterval: Duration = Duration.ofMillis(250),
) : StreamControl, AutoCloseable {

    private val log = Logger.getLogger(BinanceStreamClient::class.java.name)
    private val nextId = AtomicInteger(1)

    @Volatile private var symbols: List<String> = emptyList()
    @Volatile private var running = false
    @Volatile private var pausedUntil: Instant = Instant.MIN
    @Volatile private var connectedSince: Instant? = null
    @Volatile private var session: Session? = null
    private var worker: Thread? = null

    override val status: StreamStatus get() = connectedSince.let { StreamStatus(it != null, it) }

    fun start(symbols: List<String>) {
        this.symbols = symbols
        running = true
        worker = Thread.ofVirtual().name("binance-stream").start { loop() }
    }

    override fun resubscribe(symbols: List<String>) {
        this.symbols = symbols
        session?.resubscribe(symbols)
    }

    override fun pause(d: Duration) {
        pausedUntil = clock.instant().plus(d)
        session?.let {
            it.socket?.abort()
            it.end()
        }
    }

    override fun close() {
        running = false
        session?.let {
            it.socket?.abort()
            it.end()
        }
        worker?.interrupt()
    }

    private fun loop() {
        var backoff = backoffBase
        try {
            while (running) {
                val pause = Duration.between(clock.instant(), pausedUntil)
                if (pause > Duration.ZERO) {
                    Thread.sleep(pause)
                    continue
                }
                val current = Session().also { session = it }
                try {
                    current.socket = connect(current).get(10, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    log.log(Level.WARNING, "바이낸스 웹소켓 연결 실패", e)
                    current.end()
                }
                if (current.opened) backoff = backoffBase
                while (!current.closed.await(1, TimeUnit.SECONDS)) {
                    if (current.opened && Duration.between(current.lastFrameAt, clock.instant()) > idleTimeout) {
                        log.warning("바이낸스에서 ${idleTimeout} 동안 받은 게 없어 다시 연결합니다")
                        current.socket?.abort()
                        current.end()
                    }
                }
                session = null
                if (!running) return
                if (Duration.between(clock.instant(), pausedUntil) > Duration.ZERO) continue
                Thread.sleep(backoff)
                backoff = minOf(backoff.multipliedBy(2), backoffMax)
            }
        } catch (_: InterruptedException) {
            // 종료
        }
    }

    private fun deliver(text: String) {
        try {
            BinanceStreamParser.parse(text)?.let(listener::onMessage)
        } catch (e: Exception) {
            log.log(Level.WARNING, "바이낸스 메시지 처리 실패", e)
        }
    }

    private fun ids(count: Int): Int = nextId.getAndAdd(count)

    private inner class Session : WebSocket.Listener {
        val closed = CountDownLatch(1)
        @Volatile var socket: WebSocket? = null
        @Volatile var opened = false
        @Volatile var lastFrameAt: Instant = clock.instant()
        private val ended = AtomicBoolean(false)
        private val text = StringBuilder()
        private val outbox = LinkedBlockingQueue<String>()
        private var subscribed: Set<String> = emptySet()
        private var sender: Thread? = null

        override fun onOpen(webSocket: WebSocket) {
            // 열리는 도중에 끊기면(pause·감시) 상태가 "연결됨"으로 남지 않게, 열기와 끊기를 같은 잠금으로 묶는다.
            synchronized(this) {
                if (ended.get()) {
                    webSocket.abort()
                    return
                }
                socket = webSocket
                lastFrameAt = clock.instant()
                opened = true
                val list = symbols
                subscribed = list.toSet()
                enqueue(BinanceStreamParser.subscribeMessages(list, ids(batches(list.size))))
                sender = Thread.ofVirtual().name("binance-stream-sender").start { sendLoop(webSocket) }
                val now = clock.instant()
                connectedSince = now
                listener.onConnected(now)
            }
            webSocket.request(1)
        }

        /** 지금 구독과의 차이만 SUBSCRIBE / UNSUBSCRIBE 한다. */
        fun resubscribe(list: List<String>) = synchronized(this) {
            if (!opened || ended.get()) return
            val wanted = list.toSet()
            val added = list.filter { it !in subscribed }
            val removed = subscribed.filter { it !in wanted }.sorted()
            subscribed = wanted
            if (added.isNotEmpty()) enqueue(BinanceStreamParser.subscribeMessages(added, ids(batches(added.size))))
            if (removed.isNotEmpty()) enqueue(BinanceStreamParser.unsubscribeMessages(removed, ids(batches(removed.size))))
        }

        private fun batches(n: Int) = maxOf(1, (n + BinanceStreamParser.BATCH - 1) / BinanceStreamParser.BATCH)

        private fun enqueue(messages: List<String>) = outbox.addAll(messages)

        /** 바이낸스 제한(연결당 초당 5개) 안에서 보내도록 메시지 사이에 [sendInterval]을 둔다. */
        private fun sendLoop(webSocket: WebSocket) {
            try {
                while (!ended.get()) {
                    val message = outbox.take()
                    webSocket.sendText(message, true).get(10, TimeUnit.SECONDS)
                    Thread.sleep(sendInterval)
                }
            } catch (_: InterruptedException) {
                // 세션 끝
            } catch (e: Exception) {
                log.log(Level.WARNING, "바이낸스 구독 메시지 전송 실패, 다시 연결합니다", e)
                webSocket.abort()
                end()
            }
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            lastFrameAt = clock.instant()
            text.append(data)
            if (last) {
                deliver(text.toString())
                text.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onPing(webSocket: WebSocket, message: java.nio.ByteBuffer): CompletionStage<*>? {
            lastFrameAt = clock.instant()
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            log.info("바이낸스 웹소켓이 닫혔습니다: $statusCode $reason")
            end()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            log.log(Level.WARNING, "바이낸스 웹소켓 오류", error)
            end()
        }

        fun end() = synchronized(this) {
            if (!ended.compareAndSet(false, true)) return
            sender?.interrupt()
            if (opened) {
                connectedSince = null
                listener.onDisconnected(clock.instant())
            }
            closed.countDown()
        }
    }

    companion object {
        fun connector(url: String, http: HttpClient = HttpClient.newHttpClient()): (WebSocket.Listener) -> CompletableFuture<WebSocket> =
            { l -> http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI(url), l) }
    }
}
