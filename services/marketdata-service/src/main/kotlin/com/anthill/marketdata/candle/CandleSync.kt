package com.anthill.marketdata.candle

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.Priority
import com.anthill.marketdata.source.SourceClient
import org.springframework.stereotype.Component
import java.time.Instant

data class SyncResult(val upserted: List<Candle>, val deleted: List<Instant>, val reachedHistoryStart: Boolean)

/**
 * "이 구간을 소스와 똑같이 만든다." 소스 REST로 DB를 바꾸는 일은 모두 여기를 거친다.
 * 비교는 값 기준이고, 끝나면 그 구간을 확인 구간(coverage)에 더한다.
 * - 정착: [settledBefore] 이후 캔들(방금 마감돼 업비트 쪽에서 아직 바뀔 수 있는 것)은 저장만 하고, 지우지 않고, 확인 구간에 넣지 않는다.
 * - 화면 푸시: 정정(audit)이면 바뀐 것 전부, 아니면 확인 구간 끝 이후(시청자가 실시간으로 봤을 수 있는 칸)만 보낸다.
 *   과거 채우기·과거 조회로 처음 저장하는 옛 캔들은 보내지 않는다.
 */
@Component
class CandleSync(
    private val source: SourceClient,
    private val candles: CandleRepository,
    private val coverage: CoverageRepository,
    private val sink: CandleChangeSink,
    private val corrections: CorrectionLog,
) {

    fun syncWindow(i: InstrumentId, u: CandleUnit, from: Instant, to: Instant, priority: Priority, audit: Boolean, settledBefore: Instant = to): SyncResult {
        val fetched = ArrayList<Candle>()
        var cursor = to
        var reachedStart: Boolean
        while (true) {
            val page = source.candles(i, u, cursor, PAGE, priority)
            page.filterTo(fetched) { it.start >= from && it.start < to }
            reachedStart = page.size < PAGE
            if (reachedStart || page.last().start <= from) break
            cursor = page.last().start
        }
        return apply(i, u, from, to, fetched, audit, reachedStart, settledBefore)
    }

    fun syncBefore(i: InstrumentId, u: CandleUnit, to: Instant, priority: Priority, settledBefore: Instant = to): SyncResult {
        val page = source.candles(i, u, to, PAGE, priority)
        val reachedStart = page.size < PAGE
        val from = if (reachedStart) Instant.EPOCH else page.last().start
        return apply(i, u, from, to, page, audit = false, reachedStart, settledBefore)
    }

    private fun apply(
        i: InstrumentId, u: CandleUnit, from: Instant, to: Instant, fetched: List<Candle>, audit: Boolean, reachedStart: Boolean,
        settledBefore: Instant,
    ): SyncResult {
        val settled = minOf(to, settledBefore)
        val visibleFrom = coverage.end(i, u)
        val stored = candles.findRange(i, u, from, to).associateBy { it.start }
        val incoming = fetched.associateBy { it.start }
        val upserted = incoming.values.filter { c -> stored[c.start]?.sameValuesAs(c) != true }.sortedBy { it.start }
        val deleted = stored.keys.filter { it !in incoming && it < settled }.sorted()
        candles.upsert(upserted)
        candles.delete(i, u, deleted)
        if (settled > from) coverage.add(i, u, from, settled)
        if (audit) {
            upserted.forEach { corrections.record(if (stored.containsKey(it.start)) "update" else "insert", stored[it.start], it) }
            deleted.forEach { corrections.record("delete", stored.getValue(it), null) }
        }
        val pushUp = if (audit) upserted else visibleFrom?.let { v -> upserted.filter { it.start >= v } }.orEmpty()
        val pushDel = if (audit) deleted else visibleFrom?.let { v -> deleted.filter { it >= v } }.orEmpty()
        if (pushUp.isNotEmpty() || pushDel.isNotEmpty()) sink.onCorrected(i, u, pushUp, pushDel)
        return SyncResult(upserted, deleted, reachedStart)
    }

    companion object {
        const val PAGE = 200

        /** 마감 뒤 이만큼 지나야 확인 구간에 넣는다(업비트 쪽 마감 처리·시계 차이 여유). */
        val SETTLE: java.time.Duration = java.time.Duration.ofSeconds(30)

        fun settledBefore(u: CandleUnit, now: Instant): Instant = u.floor(now.minus(SETTLE))
    }
}
