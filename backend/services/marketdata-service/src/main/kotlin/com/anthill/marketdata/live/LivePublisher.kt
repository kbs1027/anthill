package com.anthill.marketdata.live

import com.anthill.contract.Candle
import com.anthill.contract.CandleState
import com.anthill.contract.CandleUnit
import com.anthill.contract.CandleUpdateEvent
import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentId
import com.anthill.eventbus.EventPublisher
import com.anthill.marketdata.candle.CandleChangeSink
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/** 1초마다 바뀐 캔들·정정·티커를 `chart.*` 이벤트로 보낸다. 바뀐 것이 없으면 아무것도 보내지 않는다. */
class LivePublisher(
    private val book: LiveCandleBook,
    private val tickers: TickerCache,
    private val publisher: EventPublisher,
    private val clock: Clock,
) : CandleChangeSink {

    private val corrections = ConcurrentLinkedQueue<CandleUpdateEvent>()

    override fun onCorrected(i: InstrumentId, u: CandleUnit, upserted: List<Candle>, deleted: List<Instant>) {
        upserted.forEach { corrections += CandleUpdateEvent(i, u, it.start, it, CandleState.CORRECTED) }
        deleted.forEach { corrections += CandleUpdateEvent(i, u, it, null, CandleState.REMOVED) }
    }

    fun tick() {
        book.sweep(clock.instant())
        book.drainUpdates().forEach {
            publisher.publish(EventTypes.CHART_CANDLE_UPDATE, it.instrument.value, CandleUpdateEvent(it.instrument, it.unit, it.start, it.candle, it.state))
        }
        while (true) {
            val e = corrections.poll() ?: break
            publisher.publish(EventTypes.CHART_CANDLE_UPDATE, e.instrument.value, e)
        }
        tickers.drainChanged().forEach { publisher.publish(EventTypes.CHART_TICKER_UPDATE, it.instrument.value, it) }
    }
}
