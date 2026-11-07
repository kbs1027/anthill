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
        val end = to ?: u.floor(clock.instant())
        val current = if (to == null) live.current(i, u) else null
        var result = emptyList<Candle>()
        var hasMore = true
        val covered = coverage.end(i, u)
        val since = live.streamConnectedSince(i.source)
        var cursor = end
        if (to == null && covered != null && since != null && since <= covered && end > covered) {
            result = candles.findRange(i, u, covered, end).takeLast(count)
            cursor = covered
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
