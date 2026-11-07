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
 */
@Component
class CandleSync(
    private val source: SourceClient,
    private val candles: CandleRepository,
    private val coverage: CoverageRepository,
    private val sink: CandleChangeSink,
    private val corrections: CorrectionLog,
) {

    fun syncWindow(i: InstrumentId, u: CandleUnit, from: Instant, to: Instant, priority: Priority, audit: Boolean): SyncResult {
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
        return apply(i, u, from, to, fetched, audit, reachedStart)
    }

    fun syncBefore(i: InstrumentId, u: CandleUnit, to: Instant, priority: Priority): SyncResult {
        val page = source.candles(i, u, to, PAGE, priority)
        val reachedStart = page.size < PAGE
        val from = if (reachedStart) Instant.EPOCH else page.last().start
        return apply(i, u, from, to, page, audit = false, reachedStart)
    }

    private fun apply(
        i: InstrumentId, u: CandleUnit, from: Instant, to: Instant, fetched: List<Candle>, audit: Boolean, reachedStart: Boolean,
    ): SyncResult {
        val stored = candles.findRange(i, u, from, to).associateBy { it.start }
        val incoming = fetched.associateBy { it.start }
        val upserted = incoming.values.filter { c -> stored[c.start]?.sameValuesAs(c) != true }.sortedBy { it.start }
        val deleted = stored.keys.filter { it !in incoming }.sorted()
        candles.upsert(upserted)
        candles.delete(i, u, deleted)
        coverage.add(i, u, from, to)
        // 정정(검사·복구)일 때만 기록하고 화면에 다시 보낸다. 과거 채우기·과거 조회는 처음 저장하는 것이라 정정이 아니다.
        if (audit && (upserted.isNotEmpty() || deleted.isNotEmpty())) {
            sink.onCorrected(i, u, upserted, deleted)
            upserted.forEach { corrections.record(if (stored.containsKey(it.start)) "update" else "insert", stored[it.start], it) }
            deleted.forEach { corrections.record("delete", stored.getValue(it), null) }
        }
        return SyncResult(upserted, deleted, reachedStart)
    }

    companion object {
        const val PAGE = 200
    }
}
