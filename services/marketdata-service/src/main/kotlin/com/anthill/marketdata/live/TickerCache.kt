package com.anthill.marketdata.live

import com.anthill.contract.InstrumentId
import com.anthill.contract.TickerSnapshot
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** 종목 목록에 보여줄 최신 티커. 바뀐 것은 1초 발행 때 함께 나간다. */
class TickerCache {
    private val latest = ConcurrentHashMap<InstrumentId, TickerSnapshot>()
    private val changed = ConcurrentHashMap.newKeySet<InstrumentId>()

    fun put(t: TickerSnapshot) {
        latest[t.instrument] = t
        changed += t.instrument
    }

    fun all(): List<TickerSnapshot> = latest.values.toList()

    fun get(i: InstrumentId): TickerSnapshot? = latest[i]

    fun amount24h(i: InstrumentId): BigDecimal? = latest[i]?.accTradePrice24h

    fun drainChanged(): List<TickerSnapshot> {
        val ids = changed.toList()
        changed.removeAll(ids.toSet())
        return ids.mapNotNull { latest[it] }
    }
}
