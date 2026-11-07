package com.anthill.marketdata.live

import com.anthill.contract.Candle
import com.anthill.contract.CandleState
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.SourceCandleEvent
import com.anthill.contract.TickerSnapshot
import com.anthill.contract.TradeEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.TreeMap

fun interface ClosedCandleSink {
    fun onClosed(authoritative: Candle)
}

data class LiveUpdate(val instrument: InstrumentId, val unit: CandleUnit, val start: Instant, val candle: Candle?, val state: CandleState)

/**
 * 진행 중 캔들(전 단위)의 상태 기계.
 * - 분 단위: 체결로 갱신하고, 업비트 분봉이 우리가 반영한 마지막 체결보다 새롭거나 같으면 업비트 값을 그대로 쓴다.
 *   더 오래된 분봉일 때만 고가·저가·거래량·거래대금이 줄지 않게 큰 쪽을 유지한다.
 * - 일봉: 티커 값 그대로. 주·월봉: 마감 일봉 + 티커 당일 일봉의 합. 체결을 누적하지 않는다.
 * 같은 체결 순번(sequential_id)은 한 번만 반영한다.
 */
class LiveCandleBook(private val clock: Clock, private val closeGrace: Duration, private val sink: ClosedCandleSink) {

    private class Slot(var display: Candle, var authoritative: Candle?, var lastTradeAt: Instant?)

    private val slots = HashMap<Pair<InstrumentId, CandleUnit>, Slot>()
    private val closedDays = HashMap<InstrumentId, TreeMap<Instant, Candle>>()
    private val seenTrades = HashMap<InstrumentId, LinkedHashSet<Long>>()
    private val pendingLive = LinkedHashMap<Pair<InstrumentId, CandleUnit>, LiveUpdate>()
    private val pendingClosed = ArrayList<LiveUpdate>()

    @Synchronized
    fun seedClosedDays(i: InstrumentId, days: List<Candle>) {
        val map = closedDays.getOrPut(i) { TreeMap() }
        days.filter { it.unit == CandleUnit.DAY }.forEach { map[it.start] = it }
        slots[i to CandleUnit.DAY]?.let { recomputePeriods(i, it.display) }
    }

    @Synchronized
    fun onTrade(e: TradeEvent) {
        val seen = seenTrades.getOrPut(e.instrument) { LinkedHashSet() }
        if (!seen.add(e.sequentialId)) return
        if (seen.size > SEEN_LIMIT) seen.remove(seen.first())
        for (u in MINUTE_UNITS) {
            val key = e.instrument to u
            val bucket = u.floor(e.tradedAt)
            val slot = slots[key]
            when {
                slot == null -> open(key, fromTrade(e, u, bucket), null, e.tradedAt)
                bucket < slot.display.start -> Unit
                bucket == slot.display.start -> {
                    val c = slot.display
                    slot.display = c.copy(
                        high = c.high.max(e.price), low = c.low.min(e.price), close = e.price,
                        volume = c.volume + e.volume, amount = c.amount + e.price * e.volume,
                    )
                    slot.lastTradeAt = maxOf(slot.lastTradeAt ?: e.tradedAt, e.tradedAt)
                    markLive(key, slot.display)
                }
                else -> {
                    close(key, slot)
                    open(key, fromTrade(e, u, bucket), null, e.tradedAt)
                }
            }
        }
    }

    @Synchronized
    fun onCandle(e: SourceCandleEvent) {
        val c = e.candle
        if (c.unit !in MINUTE_UNITS) return
        val key = c.instrument to c.unit
        val slot = slots[key]
        when {
            slot == null -> open(key, c, c, e.lastTradeAt)
            c.start < slot.display.start -> sink.onClosed(c)
            c.start == slot.display.start -> {
                if (slot.authoritative?.sameValuesAs(c) == true) return
                slot.authoritative = c
                val applied = slot.lastTradeAt
                val next = if (applied == null || !e.lastTradeAt.isBefore(applied)) {
                    slot.lastTradeAt = e.lastTradeAt
                    c
                } else {
                    val d = slot.display
                    d.copy(open = c.open, high = d.high.max(c.high), low = d.low.min(c.low), volume = d.volume.max(c.volume), amount = d.amount.max(c.amount))
                }
                if (!next.sameValuesAs(slot.display)) {
                    slot.display = next
                    markLive(key, next)
                }
            }
            else -> {
                close(key, slot)
                open(key, c, c, e.lastTradeAt)
            }
        }
    }

    @Synchronized
    fun onTicker(t: TickerSnapshot) {
        val day = t.day
        val key = t.instrument to CandleUnit.DAY
        val slot = slots[key]
        when {
            slot == null -> open(key, day, day, null)
            day.start < slot.display.start -> return
            day.start == slot.display.start -> {
                if (slot.display.sameValuesAs(day)) return
                slot.display = day
                slot.authoritative = day
                markLive(key, day)
            }
            else -> {
                close(key, slot)
                open(key, day, day, null)
            }
        }
        recomputePeriods(t.instrument, day)
    }

    @Synchronized
    fun sweep(now: Instant) {
        val due = slots.filter { (key, slot) -> !key.second.next(slot.display.start).plus(closeGrace).isAfter(now) }
        due.forEach { (key, slot) ->
            close(key, slot)
            slots.remove(key)
        }
    }

    @Synchronized
    fun current(i: InstrumentId, u: CandleUnit): Candle? = slots[i to u]?.display

    @Synchronized
    fun drainUpdates(): List<LiveUpdate> {
        val out = ArrayList<LiveUpdate>(pendingClosed.size + pendingLive.size)
        out += pendingClosed
        out += pendingLive.values
        pendingClosed.clear()
        pendingLive.clear()
        return out
    }

    private fun recomputePeriods(i: InstrumentId, today: Candle) {
        val days = closedDays.getOrPut(i) { TreeMap() }
        val keepFrom = minOf(CandleUnit.WEEK.floor(today.start), CandleUnit.MONTH.floor(today.start))
        days.headMap(keepFrom).clear()
        for (u in listOf(CandleUnit.WEEK, CandleUnit.MONTH)) {
            val start = u.floor(today.start)
            val parts = days.subMap(start, today.start).values + today
            val first = parts.first()
            val period = Candle(
                i, u, start, first.open, parts.maxOf { it.high }, parts.minOf { it.low }, today.close,
                parts.fold(java.math.BigDecimal.ZERO) { s, c -> s + c.volume }, parts.fold(java.math.BigDecimal.ZERO) { s, c -> s + c.amount },
            )
            val key = i to u
            val slot = slots[key]
            when {
                slot == null -> open(key, period, null, null)
                start < slot.display.start -> Unit
                start == slot.display.start -> if (!slot.display.sameValuesAs(period)) {
                    slot.display = period
                    markLive(key, period)
                }
                else -> {
                    close(key, slot)
                    open(key, period, null, null)
                }
            }
        }
    }

    private fun open(key: Pair<InstrumentId, CandleUnit>, c: Candle, authoritative: Candle?, lastTradeAt: Instant?) {
        val slot = Slot(c, authoritative, lastTradeAt)
        slots[key] = slot
        markLive(key, c)
    }

    private fun close(key: Pair<InstrumentId, CandleUnit>, slot: Slot) {
        val final = slot.authoritative ?: slot.display
        sink.onClosed(final)
        if (key.second == CandleUnit.DAY) closedDays.getOrPut(key.first) { TreeMap() }[final.start] = final
        pendingLive.remove(key)
        pendingClosed += LiveUpdate(key.first, key.second, final.start, final, CandleState.CLOSED)
    }

    private fun markLive(key: Pair<InstrumentId, CandleUnit>, c: Candle) {
        pendingLive[key] = LiveUpdate(key.first, key.second, c.start, c, CandleState.LIVE)
    }

    private fun fromTrade(e: TradeEvent, u: CandleUnit, bucket: Instant) =
        Candle(e.instrument, u, bucket, e.price, e.price, e.price, e.price, e.volume, e.price * e.volume)

    companion object {
        val MINUTE_UNITS = CandleUnit.entries.filter { it.minutes != null }
        private const val SEEN_LIMIT = 10_000
    }
}
