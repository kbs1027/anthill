package com.anthill.marketdata.ingest

import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentListEvent
import com.anthill.contract.SecondCandleEvent
import com.anthill.contract.SourceCandleEvent
import com.anthill.contract.StreamStatus
import com.anthill.contract.StreamStatusEvent
import com.anthill.contract.TickerEvent
import com.anthill.contract.TradeEvent
import com.anthill.contract.ViewingEvent
import com.anthill.eventbus.EventEnvelope
import com.anthill.eventbus.EventHandler
import com.anthill.marketdata.candle.InstrumentRepository
import com.anthill.marketdata.live.LiveCandleBook
import com.anthill.marketdata.live.StreamStatusTracker
import com.anthill.marketdata.live.TickerCache
import com.anthill.marketdata.live.ViewingTracker
import java.time.Clock

class TradeHandler(private val book: LiveCandleBook, private val tracker: StreamStatusTracker) : EventHandler<TradeEvent> {
    override val type = EventTypes.MARKET_TRADE
    override val payloadType = TradeEvent::class.java
    override fun handle(key: String, payload: TradeEvent, envelope: EventEnvelope) {
        tracker.touch(payload.instrument.source, envelope.occurredAt)
        book.onTrade(payload)
    }
}

class SourceCandleHandler(private val book: LiveCandleBook, private val tracker: StreamStatusTracker) : EventHandler<SourceCandleEvent> {
    override val type = EventTypes.MARKET_CANDLE
    override val payloadType = SourceCandleEvent::class.java
    override fun handle(key: String, payload: SourceCandleEvent, envelope: EventEnvelope) {
        tracker.touch(payload.candle.instrument.source, envelope.occurredAt)
        book.onCandle(payload)
    }
}

/** 바이낸스 1초 캔들. 분 단위 진행 중 캔들을 갱신한다(일·주·월봉은 티커 경로). */
class SecondCandleHandler(private val book: LiveCandleBook, private val tracker: StreamStatusTracker) : EventHandler<SecondCandleEvent> {
    override val type = EventTypes.MARKET_SECOND_CANDLE
    override val payloadType = SecondCandleEvent::class.java
    override fun handle(key: String, payload: SecondCandleEvent, envelope: EventEnvelope) {
        tracker.touch(payload.candle.instrument.source, envelope.occurredAt)
        book.onSecondCandle(payload)
    }
}

class TickerHandler(private val book: LiveCandleBook, private val tickers: TickerCache, private val tracker: StreamStatusTracker) : EventHandler<TickerEvent> {
    override val type = EventTypes.MARKET_TICKER
    override val payloadType = TickerEvent::class.java
    override fun handle(key: String, payload: TickerEvent, envelope: EventEnvelope) {
        tracker.touch(payload.ticker.instrument.source, envelope.occurredAt)
        tickers.put(payload.ticker)
        book.onTicker(payload.ticker)
    }
}

class StreamStatusHandler(private val tracker: StreamStatusTracker) : EventHandler<StreamStatusEvent> {
    override val type = EventTypes.MARKET_STREAM_STATUS
    override val payloadType = StreamStatusEvent::class.java
    override fun handle(key: String, payload: StreamStatusEvent, envelope: EventEnvelope) {
        // 어댑터는 5초마다 같은 상태를 다시 알린다(연결이면 at = 연결 시각). 받은 시각은 살아 있다는 신호다.
        tracker.update(payload.source, StreamStatus(payload.connected, if (payload.connected) payload.at else null), payload.at)
        tracker.touch(payload.source, envelope.occurredAt)
    }
}

class InstrumentHandler(private val instruments: InstrumentRepository, private val clock: Clock) : EventHandler<InstrumentListEvent> {
    override val type = EventTypes.MARKET_INSTRUMENT
    override val payloadType = InstrumentListEvent::class.java
    override fun handle(key: String, payload: InstrumentListEvent, envelope: EventEnvelope) {
        instruments.sync(payload.source, payload.instruments, clock.instant())
    }
}

class ViewingHandler(private val viewing: ViewingTracker) : EventHandler<ViewingEvent> {
    override val type = EventTypes.REALTIME_VIEWING
    override val payloadType = ViewingEvent::class.java
    override fun handle(key: String, payload: ViewingEvent, envelope: EventEnvelope) = viewing.update(payload)
}
