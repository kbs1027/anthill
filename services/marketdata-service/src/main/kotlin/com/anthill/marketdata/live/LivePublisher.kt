package com.anthill.marketdata.live

import com.anthill.contract.Candle
import com.anthill.contract.CandleState
import com.anthill.contract.CandleUnit
import com.anthill.contract.CandleUpdateEvent
import com.anthill.contract.EventTypes
import com.anthill.contract.FeedStatusEvent
import com.anthill.contract.InstrumentId
import com.anthill.eventbus.EventPublisher
import com.anthill.marketdata.candle.CandleChangeSink
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 1초마다 바뀐 캔들·정정·티커를 `chart.*` 이벤트로 보낸다. 바뀐 것이 없으면 아무것도 보내지 않는다.
 * 예비 서버([isLeader]가 false)는 계산만 하고 보내지 않는다. 이어받기 사이에 닫힌 칸은 들고 있다가 [onPromoted] 때 보낸다.
 * [feedConnected]를 주면 주 서버가 틱마다 시세 원천 상태를 알린다(화면의 시세 지연 표시).
 */
class LivePublisher(
    private val book: LiveCandleBook,
    private val tickers: TickerCache,
    private val publisher: EventPublisher,
    private val clock: Clock,
    private val isLeader: () -> Boolean = { true },
    private val feedConnected: (() -> Boolean)? = null,
    private val keepClosed: Duration = Duration.ofSeconds(5),
) : CandleChangeSink {

    private val corrections = ConcurrentLinkedQueue<CandleUpdateEvent>()
    private val heldClosed = ArrayDeque<Pair<Instant, LiveUpdate>>()

    override fun onCorrected(i: InstrumentId, u: CandleUnit, upserted: List<Candle>, deleted: List<Instant>) {
        upserted.forEach { corrections += CandleUpdateEvent(i, u, it.start, it, CandleState.CORRECTED) }
        deleted.forEach { corrections += CandleUpdateEvent(i, u, it, null, CandleState.REMOVED) }
    }

    @Synchronized
    fun tick() {
        val now = clock.instant()
        book.sweep(now)
        val updates = book.drainUpdates()
        if (!isLeader()) {
            updates.filter { it.state == CandleState.CLOSED }.forEach { heldClosed.addLast(now to it) }
            trimHeld(now)
            corrections.clear()
            tickers.drainChanged()
            return
        }
        updates.forEach(::publish)
        while (true) {
            val e = corrections.poll() ?: break
            publisher.publish(EventTypes.CHART_CANDLE_UPDATE, e.instrument.value, e)
        }
        tickers.drainChanged().forEach { publisher.publish(EventTypes.CHART_TICKER_UPDATE, it.instrument.value, it) }
        feedConnected?.let { publisher.publish(EventTypes.CHART_FEED_STATUS, "feed", FeedStatusEvent(it(), now)) }
    }

    /** 이어받았다: 들고 있던 마감을 보내고, 옛 주 서버가 마지막에 못 보낸 값까지 다음 틱에 전부 다시 보낸다. */
    @Synchronized
    fun onPromoted() {
        trimHeld(clock.instant())
        heldClosed.forEach { publish(it.second) }
        heldClosed.clear()
        book.markAllLive()
        tickers.markAllChanged()
    }

    private fun publish(u: LiveUpdate) =
        publisher.publish(EventTypes.CHART_CANDLE_UPDATE, u.instrument.value, CandleUpdateEvent(u.instrument, u.unit, u.start, u.candle, u.state))

    private fun trimHeld(now: Instant) {
        val oldest = now.minus(keepClosed)
        while (heldClosed.firstOrNull()?.first?.isBefore(oldest) == true) heldClosed.removeFirst()
    }
}
