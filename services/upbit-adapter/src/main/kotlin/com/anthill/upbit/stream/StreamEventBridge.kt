package com.anthill.upbit.stream

import com.anthill.contract.EventTypes
import com.anthill.contract.SourceCandleEvent
import com.anthill.contract.StreamStatusEvent
import com.anthill.contract.TickerEvent
import com.anthill.eventbus.EventPublisher
import com.anthill.upbit.rest.UpbitJson
import java.time.Instant

/** 웹소켓 메시지를 공통 계약 이벤트(`market.*`)로 바꿔 발행한다. key는 종목 ID, 연결 상태는 소스. */
class StreamEventBridge(private val publisher: EventPublisher) : StreamListener {

    override fun onMessage(m: UpbitMessage) = when (m) {
        is UpbitCandleMsg -> publisher.publish(EventTypes.MARKET_CANDLE, m.candle.instrument.value, SourceCandleEvent(m.candle, m.lastTradeAt, m.snapshot))
        is UpbitTradeMsg -> publisher.publish(EventTypes.MARKET_TRADE, m.trade.instrument.value, m.trade)
        is UpbitTickerMsg -> publisher.publish(EventTypes.MARKET_TICKER, m.ticker.instrument.value, TickerEvent(m.ticker))
    }

    override fun onConnected(at: Instant) =
        publisher.publish(EventTypes.MARKET_STREAM_STATUS, UpbitJson.SOURCE, StreamStatusEvent(UpbitJson.SOURCE, true, at))

    override fun onDisconnected(at: Instant) =
        publisher.publish(EventTypes.MARKET_STREAM_STATUS, UpbitJson.SOURCE, StreamStatusEvent(UpbitJson.SOURCE, false, at))
}
