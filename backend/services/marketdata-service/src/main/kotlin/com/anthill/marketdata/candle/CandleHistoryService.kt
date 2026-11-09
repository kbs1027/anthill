package com.anthill.marketdata.candle

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.Priority
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

data class CandlePage(val candles: List<Candle>, val live: Candle?, val hasMore: Boolean)

/**
 * 화면용 과거 조회. 확인 구간이면 DB에서 바로 읽고, 아니면 소스에서 200개를 받아 맞춘 뒤 읽는다.
 * 스트림이 확인 구간 끝 이전부터 끊기지 않았으면 그 뒤 구간(스트림 구간)도 DB 값을 믿는다.
 * 방금 마감돼 정착 전인 캔들은 소스에서 받아 보여 주되 확인 구간에는 넣지 않는다([CandleSync.SETTLE]).
 */
@Service
class CandleHistoryService(
    private val candles: CandleRepository,
    private val coverage: CoverageRepository,
    private val sync: CandleSync,
    private val live: LiveView,
    private val clock: Clock,
) {

    fun page(i: InstrumentId, u: CandleUnit, to: Instant?, count: Int): CandlePage {
        val now = clock.instant()
        val end = to ?: u.floor(now)
        val current = if (to == null) live.current(i, u) else null
        var result = emptyList<Candle>()
        var hasMore = true
        var cursor = end
        if (to == null) {
            val covered = coverage.end(i, u)
            val since = live.streamConnectedSince(i.source)
            val settled = minOf(end, CandleSync.settledBefore(u, now))
            if (covered != null && since != null && since <= covered && end > covered) {
                result = candles.findRange(i, u, covered, end).takeLast(count)
                cursor = covered
            } else if (settled < end && (covered == null || covered < end)) {
                // 가장 최근 마감 캔들은 아직 정착 전이라 확인 구간에 넣지 않는다. 받아서 저장한 값을 그대로 보여 준다.
                sync.syncBefore(i, u, end, Priority.USER, settledBefore = settled)
                result = candles.findRange(i, u, settled, end).takeLast(count)
                cursor = settled
            }
        }
        while (result.size < count) {
            val start = coverage.coveredStartFor(i, u, cursor) ?: run {
                sync.syncBefore(i, u, cursor, Priority.USER)
                checkNotNull(coverage.coveredStartFor(i, u, cursor)) { "coverage missing after sync for $i $u before $cursor" }
            }
            result = candles.findBefore(i, u, cursor, count - result.size).filter { it.start >= start } + result
            if (start == Instant.EPOCH) {
                hasMore = false
                break
            }
            if (result.size < count) cursor = start
        }
        return CandlePage(result, current, hasMore)
    }
}
