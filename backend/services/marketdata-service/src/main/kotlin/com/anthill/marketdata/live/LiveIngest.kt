package com.anthill.marketdata.live

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.marketdata.candle.CandleChangeSink
import com.anthill.marketdata.candle.CandleRepository
import com.anthill.marketdata.candle.CoverageRepository
import com.anthill.marketdata.candle.LiveView
import java.util.concurrent.Executor

/** 과거 조회에 진행 중 캔들과 스트림 연결 시각을 알려준다. */
class LiveIngest(private val book: LiveCandleBook, private val tracker: StreamStatusTracker) : LiveView {
    override fun current(i: InstrumentId, u: CandleUnit): Candle? = book.current(i, u)
    override fun streamConnectedSince(source: String) = tracker.connectedSince(source)
}

/**
 * 마감된 캔들을 저장한다. 이미 소스 REST로 확인한 구간이면 건드리지 않는다(검증된 값을 늦은 이벤트가 덮지 않도록).
 * 늦게 온 값을 저장하면 [changes]로 화면에 정정을 보낸다.
 * 저장은 [executor]에서 해서, 이벤트 처리 잠금을 DB 작업이 붙잡지 않게 한다.
 */
class ClosedCandlePersister(
    private val candles: CandleRepository,
    private val coverage: CoverageRepository,
    private val executor: Executor,
    private val changes: CandleChangeSink,
) : ClosedCandleSink {
    override fun onClosed(c: Candle, late: Boolean) = executor.execute {
        if (coverage.coveredStartFor(c.instrument, c.unit, c.unit.next(c.start)) != null) return@execute
        candles.upsert(listOf(c))
        // 늦게 온 업비트 값은 이미 화면에 마감으로 나간 칸을 고친다(리뷰 C1).
        if (late) changes.onCorrected(c.instrument, c.unit, listOf(c), emptyList())
    }
}
