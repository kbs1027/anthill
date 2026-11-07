package com.anthill.marketdata.live

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
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
 * 저장은 [executor]에서 해서, 이벤트 처리 잠금을 DB 작업이 붙잡지 않게 한다.
 */
class ClosedCandlePersister(
    private val candles: CandleRepository,
    private val coverage: CoverageRepository,
    private val executor: Executor,
) : ClosedCandleSink {
    override fun onClosed(authoritative: Candle) = executor.execute {
        val c = authoritative
        if (coverage.coveredStartFor(c.instrument, c.unit, c.unit.next(c.start)) == null) candles.upsert(listOf(c))
    }
}
