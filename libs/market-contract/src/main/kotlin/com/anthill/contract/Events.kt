package com.anthill.contract

import java.math.BigDecimal
import java.time.Instant

data class TradeEvent(
    val instrument: InstrumentId,
    val price: BigDecimal,
    val volume: BigDecimal,
    val tradedAt: Instant,
    val sequentialId: Long,
)

data class SourceCandleEvent(val candle: Candle, val lastTradeAt: Instant, val snapshot: Boolean)

data class TickerEvent(val ticker: TickerSnapshot)

data class StreamStatusEvent(val source: String, val connected: Boolean, val at: Instant)

data class InstrumentListEvent(val source: String, val instruments: List<InstrumentInfo>)

enum class CandleState { LIVE, CLOSED, CORRECTED, REMOVED }

data class CandleUpdateEvent(
    val instrument: InstrumentId,
    val unit: CandleUnit,
    val start: Instant,
    val candle: Candle?,
    val state: CandleState,
)

/** 시세 원천이 살아 있는지. marketdata 주 서버가 1초마다 게이트웨이에 알린다. */
data class FeedStatusEvent(val connected: Boolean, val at: Instant)

data class ViewingEvent(val gatewayId: String, val instruments: Set<InstrumentId>, val at: Instant)

/** 이벤트 type 이름. Kafka로 옮기면 그대로 토픽 이름이 된다. */
object EventTypes {
    const val MARKET_TRADE = "market.trade.v1"
    const val MARKET_CANDLE = "market.candle.v1"
    const val MARKET_TICKER = "market.ticker.v1"
    const val MARKET_STREAM_STATUS = "market.stream-status.v1"
    const val MARKET_INSTRUMENT = "market.instrument.v1"
    const val CHART_CANDLE_UPDATE = "chart.candle-update.v1"
    const val CHART_TICKER_UPDATE = "chart.ticker-update.v1"
    const val CHART_FEED_STATUS = "chart.feed-status.v1"
    const val REALTIME_VIEWING = "realtime.viewing.v1"
}
