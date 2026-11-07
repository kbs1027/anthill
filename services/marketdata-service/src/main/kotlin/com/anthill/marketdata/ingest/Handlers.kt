package com.anthill.marketdata.ingest

import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentListEvent
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

class TradeHandler(private val book: LiveCandleBook) : EventHandler<TradeEvent> {
    override val type = EventTypes.MARKET_TRADE
    override val payloadType = TradeEvent::class.java
    override fun handle(key: String, payload: TradeEvent, envelope: EventEnvelope) = book.onTrade(payload)
}

class SourceCandleHandler(private val book: LiveCandleBook) : EventHandler<SourceCandleEvent> {
    override val type = EventTypes.MARKET_CANDLE
    override val payloadType = SourceCandleEvent::class.java
    override fun handle(key: String, payload: SourceCandleEvent, envelope: EventEnvelope) = book.onCandle(payload)
}

class TickerHandler(private val book: LiveCandleBook, private val tickers: TickerCache) : EventHandler<TickerEvent> {
    override val type = EventTypes.MARKET_TICKER
    override val payloadType = TickerEvent::class.java
    override fun handle(key: String, payload: TickerEvent, envelope: EventEnvelope) {
        tickers.put(payload.ticker)
        book.onTicker(payload.ticker)
    }
}

class StreamStatusHandler(private val tracker: StreamStatusTracker) : EventHandler<StreamStatusEvent> {
    override val type = EventTypes.MARKET_STREAM_STATUS
    override val payloadType = StreamStatusEvent::class.java
    override fun handle(key: String, payload: StreamStatusEvent, envelope: EventEnvelope) =
        tracker.update(payload.source, StreamStatus(payload.connected, if (payload.connected) payload.at else null), payload.at)
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
