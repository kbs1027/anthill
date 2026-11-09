package com.anthill.marketdata.live

import com.anthill.contract.InstrumentId
import com.anthill.contract.TickerSnapshot
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** 종목 목록에 보여줄 최신 티커. 바뀐 것은 1초 발행 때 함께 나간다. */
class TickerCache {
    private val latest = ConcurrentHashMap<InstrumentId, TickerSnapshot>()
    private val changed = ConcurrentHashMap.newKeySet<InstrumentId>()

    /** 어댑터 두 대 중 느린 쪽의 옛 티커는 버린다. 하루 누적 거래량은 줄지 않으므로 그것으로 가른다. */
    fun put(t: TickerSnapshot) {
        var applied = false
        latest.compute(t.instrument) { _, prev ->
            if (prev != null && isOlder(t, prev)) prev else t.also { applied = true }
        }
        if (applied) changed += t.instrument
    }

    private fun isOlder(t: TickerSnapshot, prev: TickerSnapshot) =
        t.day.start < prev.day.start || (t.day.start == prev.day.start && t.day.volume < prev.day.volume)

    /** 모두 다시 내보낼 것으로 표시한다(예비 서버가 이어받을 때). */
    fun markAllChanged() { changed.addAll(latest.keys) }

    fun all(): List<TickerSnapshot> = latest.values.toList()

    fun get(i: InstrumentId): TickerSnapshot? = latest[i]

    fun amount24h(i: InstrumentId): BigDecimal? = latest[i]?.accTradePrice24h

    fun drainChanged(): List<TickerSnapshot> {
        val ids = changed.toList()
        changed.removeAll(ids.toSet())
        return ids.mapNotNull { latest[it] }
    }
}
