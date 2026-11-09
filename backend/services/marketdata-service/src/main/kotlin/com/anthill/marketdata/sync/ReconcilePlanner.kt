package com.anthill.marketdata.sync

import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import java.math.BigDecimal
import java.time.Instant

data class ReconcileJob(val instrument: InstrumentId, val unit: CandleUnit, val from: Instant, val to: Instant)

/**
 * 검사할 (종목, 단위, 구간)을 고른다. 구간은 확인 구간 끝부터 지금 열려 있는 캔들 직전까지.
 * 그 첫 캔들이 마감되고 여유 시간(1·3·5분봉 3분, 그 외 한 주기의 절반)이 지나면 검사 대상이다.
 * 순서: 화면에서 보고 있는 종목 → 24시간 거래대금 큰 순 → 짧은 단위 먼저.
 */
class ReconcilePlanner {
    fun due(
        now: Instant,
        ends: Map<Pair<InstrumentId, CandleUnit>, Instant>,
        viewed: Set<InstrumentId>,
        amount24h: (InstrumentId) -> BigDecimal?,
    ): List<ReconcileJob> = ends.mapNotNull { (key, from) ->
        val (instrument, unit) = key
        val to = unit.floor(now)
        if (to > from && !now.isBefore(unit.next(from).plus(unit.reconcileSlack))) ReconcileJob(instrument, unit, from, to) else null
    }.sortedWith(
        compareBy<ReconcileJob> { it.instrument !in viewed }
            .thenByDescending { amount24h(it.instrument) ?: BigDecimal.ZERO }
            .thenBy { it.instrument.value }
            .thenBy { it.unit.ordinal },
    )
}
