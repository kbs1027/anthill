package com.anthill.binance.stream

import com.anthill.binance.rest.BinanceJson
import com.anthill.contract.EventTypes
import com.anthill.contract.SecondCandleEvent
import com.anthill.contract.StreamStatusEvent
import com.anthill.contract.TickerEvent
import com.anthill.eventbus.EventPublisher
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 웹소켓 메시지를 공통 계약 이벤트(`market.*`)로 바꿔 발행한다. key는 종목 ID, 연결 상태는 소스(`BINANCE`).
 * 1초 캔들은 그대로 `market.second-candle.v1`로 보내고, 같은 초로 [DayBook]의 당일 일봉이 바뀌면 `market.ticker.v1`도 보낸다.
 */
class StreamEventBridge(private val publisher: EventPublisher, private val dayBook: DayBook) : StreamListener {

    private val received = AtomicLong()

    /** 지난번 부른 뒤로 받은 1초 캔들 수(수신량 로그용). */
    fun drainReceived(): Long = received.getAndSet(0)

    override fun onMessage(m: BinanceKlineMsg) {
        received.incrementAndGet()
        val key = m.second.instrument.value
        publisher.publish(EventTypes.MARKET_SECOND_CANDLE, key, SecondCandleEvent(m.second))
        dayBook.onSecond(m)?.let { publisher.publish(EventTypes.MARKET_TICKER, key, TickerEvent(it)) }
    }

    override fun onConnected(at: Instant) =
        publisher.publish(EventTypes.MARKET_STREAM_STATUS, BinanceJson.SOURCE, StreamStatusEvent(BinanceJson.SOURCE, true, at))

    override fun onDisconnected(at: Instant) =
        publisher.publish(EventTypes.MARKET_STREAM_STATUS, BinanceJson.SOURCE, StreamStatusEvent(BinanceJson.SOURCE, false, at))
}
