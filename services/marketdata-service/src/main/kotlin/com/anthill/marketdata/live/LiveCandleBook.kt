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
    /** [late]: 이미 닫은 칸에 대해 늦게 온 업비트 값. 저장소가 확인 전이면 저장하고 화면에 정정으로 보낸다. */
    fun onClosed(c: Candle, late: Boolean)
}

data class LiveUpdate(val instrument: InstrumentId, val unit: CandleUnit, val start: Instant, val candle: Candle?, val state: CandleState)

/**
 * 진행 중 캔들(전 단위)의 상태 기계.
 * - 분 단위: 체결로 갱신하고, 업비트 분봉이 우리가 반영한 마지막 체결보다 새롭거나 같으면 업비트 값을 그대로 쓴다.
 *   더 오래된 분봉일 때만 고가·저가·거래량·거래대금이 줄지 않게 큰 쪽을 유지한다.
 * - 일봉: 티커 값 그대로. 주·월봉: 마감 일봉 + 티커 당일 일봉의 합. 체결을 누적하지 않는다.
 * 같은 체결 순번(sequential_id)은 한 번만 반영하고, 이미 반영한 업비트 분봉 값에 들어 있는 체결(그 값의 마지막 체결 시각 이하)도 더하지 않는다.
 * 이미 끝난 칸(끝 + [closeGrace]가 지남)은 다시 열지 않는다. 그 칸에 대한 업비트 값은 늦은 값으로 [ClosedCandleSink]에 넘기고,
 * 저장소가 확인 전이면 저장하고 화면에 정정으로 보낸다. 지난 날짜 티커는 무시한다.
 */
class LiveCandleBook(private val clock: Clock, private val closeGrace: Duration, private val sink: ClosedCandleSink) {

    private class Slot(var display: Candle, var authoritative: Candle?, var lastTradeAt: Instant?, var upbitAt: Instant?)

    /** 칸별로 마지막에 닫거나 늦은 값으로 넘긴 값과 그 업비트 시각. 같은 값·더 옛 값을 다시 넘기지 않으려고 둔다. */
    private class Closed(val candle: Candle, val at: Instant)

    private val slots = HashMap<Pair<InstrumentId, CandleUnit>, Slot>()
    private val closedDays = HashMap<InstrumentId, TreeMap<Instant, Candle>>()
    private val seenTrades = HashMap<InstrumentId, LinkedHashSet<Long>>()
    private val pendingLive = LinkedHashMap<Pair<InstrumentId, CandleUnit>, LiveUpdate>()
    private val pendingClosed = ArrayList<LiveUpdate>()
    private val lastClosed = HashMap<Pair<InstrumentId, CandleUnit>, Closed>()

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
            if (ended(u, bucket)) continue
            val slot = slots[key]
            when {
                slot == null -> open(key, fromTrade(e, u, bucket), null, e.tradedAt, null)
                bucket < slot.display.start -> Unit
                bucket == slot.display.start -> {
                    val upbitAt = slot.upbitAt
                    if (upbitAt != null && !e.tradedAt.isAfter(upbitAt)) continue
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
                    open(key, fromTrade(e, u, bucket), null, e.tradedAt, null)
                }
            }
        }
    }

    @Synchronized
    fun onCandle(e: SourceCandleEvent) {
        val c = e.candle
        if (c.unit !in MINUTE_UNITS) return
        val key = c.instrument to c.unit
        if (ended(c.unit, c.start)) return late(key, c, e.lastTradeAt)
        val slot = slots[key]
        when {
            slot == null -> open(key, c, c, e.lastTradeAt, e.lastTradeAt)
            c.start < slot.display.start -> late(key, c, e.lastTradeAt)
            c.start == slot.display.start -> {
                if (slot.authoritative?.sameValuesAs(c) == true) return
                slot.authoritative = c
                val applied = slot.lastTradeAt
                val next = if (applied == null || !e.lastTradeAt.isBefore(applied)) {
                    slot.lastTradeAt = e.lastTradeAt
                    slot.upbitAt = e.lastTradeAt
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
                open(key, c, c, e.lastTradeAt, e.lastTradeAt)
            }
        }
    }

    @Synchronized
    fun onTicker(t: TickerSnapshot) {
        val day = t.day
        if (ended(CandleUnit.DAY, day.start)) return   // 지난 날짜 일봉(오늘 체결이 없는 종목의 REST 티커 등)
        val key = t.instrument to CandleUnit.DAY
        val slot = slots[key]
        when {
            slot == null -> open(key, day, day, null, null)
            day.start < slot.display.start -> return
            day.start == slot.display.start -> {
                if (slot.display.sameValuesAs(day)) return
                slot.display = day
                slot.authoritative = day
                markLive(key, day)
            }
            else -> {
                close(key, slot)
                open(key, day, day, null, null)
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
                slot == null -> open(key, period, null, null, null)
                start < slot.display.start -> Unit
                start == slot.display.start -> if (!slot.display.sameValuesAs(period)) {
                    slot.display = period
                    markLive(key, period)
                }
                else -> {
                    close(key, slot)
                    open(key, period, null, null, null)
                }
            }
        }
    }

    private fun open(key: Pair<InstrumentId, CandleUnit>, c: Candle, authoritative: Candle?, lastTradeAt: Instant?, upbitAt: Instant?) {
        val slot = Slot(c, authoritative, lastTradeAt, upbitAt)
        slots[key] = slot
        markLive(key, c)
    }

    private fun close(key: Pair<InstrumentId, CandleUnit>, slot: Slot) {
        // 화면에 보이는 값으로 닫는다: 업비트가 더 새로우면 이미 업비트 값이고, 업비트 분봉이 오래됐으면 그 뒤 체결까지 담겨 있다(spec 7.3).
        // 마지막 업비트 분봉으로 닫으면 그 뒤 체결이 빠진다(약 3초 간격). 업비트 최종값이 늦게 오면 late()가 정정한다.
        val final = slot.display
        sink.onClosed(final, false)
        lastClosed[key] = Closed(final, slot.lastTradeAt ?: slot.upbitAt ?: final.start)
        if (key.second == CandleUnit.DAY) closedDays.getOrPut(key.first) { TreeMap() }[final.start] = final
        pendingLive.remove(key)
        pendingClosed += LiveUpdate(key.first, key.second, final.start, final, CandleState.CLOSED)
    }

    /** 이미 닫은 칸에 대한 업비트 값. 마지막으로 닫은 값과 같거나 그보다 옛 값이면 버린다. */
    private fun late(key: Pair<InstrumentId, CandleUnit>, c: Candle, at: Instant) {
        val prev = lastClosed[key]
        if (prev != null && prev.candle.start == c.start) {
            if (prev.candle.sameValuesAs(c) || at.isBefore(prev.at)) return
            lastClosed[key] = Closed(c, at)
        }
        sink.onClosed(c, true)
    }

    private fun ended(u: CandleUnit, start: Instant) = !u.next(start).plus(closeGrace).isAfter(clock.instant())

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
