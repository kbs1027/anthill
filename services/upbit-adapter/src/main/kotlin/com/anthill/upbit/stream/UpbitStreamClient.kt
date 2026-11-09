package com.anthill.upbit.stream

import com.anthill.contract.StreamStatus
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.random.Random

interface StreamListener {
    fun onMessage(m: UpbitMessage)
    fun onConnected(at: Instant)
    fun onDisconnected(at: Instant)
}

/** 다른 컴포넌트가 스트림을 다루는 창구. */
interface StreamControl {
    fun resubscribe(markets: List<String>)
    fun pause(d: Duration)
    val status: StreamStatus
}

/**
 * 업비트 웹소켓 하나로 KRW 전 종목을 구독한다. 끊기면 [backoffBase]부터 두 배씩([backoffMax]까지) 기다렸다 다시 연결하고,
 * 연결·끊김은 [StreamListener]로 알린다. 업비트는 바이너리 프레임(UTF-8 JSON)으로 보낸다.
 * 오류 없이 멈춘 연결(잠자기·NAT·네트워크 변경)은 [idleTimeout] 동안 프레임이 없으면 끊고 다시 연결한다.
 * KRW 전 종목 티커를 받으므로 정상 연결이면 1초 안에 프레임이 온다.
 * 대기 시간은 연결이 [stableAfter] 넘게 유지됐을 때만 처음으로 돌린다: 열렸다 바로 닫히는 연결로 업비트(연결 요청 초당 5회)를 두드리지 않는다.
 * 대기 시간에는 무작위 값을 섞어, 어댑터 두 대가 같은 순간에 다시 붙지 않게 한다.
 */
class UpbitStreamClient(
    private val listener: StreamListener,
    private val clock: Clock,
    private val connect: (WebSocket.Listener) -> CompletableFuture<WebSocket>,
    private val backoffBase: Duration = Duration.ofMillis(200),
    private val backoffMax: Duration = Duration.ofSeconds(5),
    private val idleTimeout: Duration = Duration.ofSeconds(2),
    private val stableAfter: Duration = Duration.ofSeconds(30),
) : StreamControl, AutoCloseable {

    private val log = Logger.getLogger(UpbitStreamClient::class.java.name)

    @Volatile private var markets: List<String> = emptyList()
    @Volatile private var running = false
    @Volatile private var pausedUntil: Instant = Instant.MIN
    @Volatile private var connectedSince: Instant? = null
    @Volatile private var session: Session? = null
    private var worker: Thread? = null

    override val status: StreamStatus get() = connectedSince.let { StreamStatus(it != null, it) }

    fun start(markets: List<String>) {
        this.markets = markets
        running = true
        worker = Thread.ofVirtual().name("upbit-stream").start { loop() }
    }

    override fun resubscribe(markets: List<String>) {
        this.markets = markets
        session?.socket?.sendText(UpbitStreamParser.subscribeMessage(markets, ticket()), true)
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
                    log.log(Level.WARNING, "upbit websocket connect failed", e)
                    current.end()
                }
                while (!current.closed.await(WATCH_MS, TimeUnit.MILLISECONDS)) {
                    if (current.opened && Duration.between(current.lastFrameAt, clock.instant()) > idleTimeout) {
                        log.warning("no upbit frame for $idleTimeout, reconnecting")
                        current.socket?.abort()
                        current.end()
                    }
                }
                session = null
                if (!running) return
                if (current.upFor() >= stableAfter) backoff = backoffBase
                if (Duration.between(clock.instant(), pausedUntil) > Duration.ZERO) continue
                Thread.sleep(jittered(backoff))
                backoff = minOf(backoff.multipliedBy(2), backoffMax)
            }
        } catch (_: InterruptedException) {
            // 종료
        }
    }

    private fun jittered(d: Duration): Duration = Duration.ofNanos((d.toNanos() * (0.5 + 0.5 * Random.nextDouble())).toLong())

    private fun deliver(text: String) {
        try {
            UpbitStreamParser.parse(text)?.let(listener::onMessage)
        } catch (e: Exception) {
            log.log(Level.WARNING, "failed to handle upbit message", e)
        }
    }

    private fun ticket() = "anthill-${UUID.randomUUID()}"

    private inner class Session : WebSocket.Listener {
        val closed = CountDownLatch(1)
        @Volatile var socket: WebSocket? = null
        @Volatile var opened = false
        @Volatile var lastFrameAt: Instant = clock.instant()
        @Volatile private var openedAt: Instant? = null
        @Volatile private var endedAt: Instant? = null
        private val ended = AtomicBoolean(false)
        private val bytes = ByteArrayOutputStream()
        private val text = StringBuilder()

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
                openedAt = lastFrameAt
                webSocket.sendText(UpbitStreamParser.subscribeMessage(markets, ticket()), true)
                val now = clock.instant()
                connectedSince = now
                listener.onConnected(now)
            }
            webSocket.request(1)
        }

        override fun onBinary(webSocket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*>? {
            lastFrameAt = clock.instant()
            val chunk = ByteArray(data.remaining())
            data.get(chunk)
            bytes.write(chunk)
            if (last) {
                deliver(bytes.toString(Charsets.UTF_8))
                bytes.reset()
            }
            webSocket.request(1)
            return null
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

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            end()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            log.log(Level.WARNING, "upbit websocket error", error)
            end()
        }

        /** 연결이 유지된 시간. 열린 적이 없으면 0. */
        fun upFor(): Duration {
            val from = openedAt ?: return Duration.ZERO
            return Duration.between(from, endedAt ?: clock.instant())
        }

        fun end() = synchronized(this) {
            if (!ended.compareAndSet(false, true)) return
            endedAt = clock.instant()
            if (opened) {
                connectedSince = null
                listener.onDisconnected(clock.instant())
            }
            closed.countDown()
        }
    }

    companion object {
        private const val WATCH_MS = 100L

        fun connector(url: String, http: HttpClient = HttpClient.newHttpClient()): (WebSocket.Listener) -> CompletableFuture<WebSocket> =
            { l -> http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI(url), l) }
    }
}
