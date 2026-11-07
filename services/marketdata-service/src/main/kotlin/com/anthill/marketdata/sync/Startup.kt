package com.anthill.marketdata.sync

import com.anthill.contract.CandleUnit
import com.anthill.contract.Priority
import com.anthill.marketdata.candle.CandleRepository
import com.anthill.marketdata.candle.CandleSync
import com.anthill.marketdata.candle.CoverageRepository
import com.anthill.marketdata.candle.InstrumentRepository
import com.anthill.marketdata.live.LiveCandleBook
import com.anthill.marketdata.live.StreamStatusTracker
import com.anthill.marketdata.live.TickerCache
import com.anthill.marketdata.source.SourceClient
import java.time.Clock
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 시작할 때 소스마다: 종목 목록 → 이번 달(또는 더 이른 이번 주)의 마감 일봉 확보 → 티커 → 스트림 상태.
 * 꺼져 있던 동안을 장애 구간으로 열어 두고, 스트림 연결 이벤트가 부르는 복구가 그 구간을 채우고 닫는다.
 * 소스(어댑터)가 아직 안 떴으면 [retry]마다 다시 시도한다.
 */
class Startup(
    private val sources: List<String>,
    private val source: SourceClient,
    private val instruments: InstrumentRepository,
    private val tickers: TickerCache,
    private val book: LiveCandleBook,
    private val tracker: StreamStatusTracker,
    private val sync: CandleSync,
    private val candles: CandleRepository,
    private val clock: Clock,
    private val outages: OutageLog? = null,
    private val coverage: CoverageRepository? = null,
    private val retry: Duration = Duration.ofSeconds(5),
) {
    private val log = Logger.getLogger(Startup::class.java.name)

    fun run() = sources.forEach { name ->
        while (true) {
            try {
                prepare(name)
                return@forEach
            } catch (e: Exception) {
                log.log(Level.WARNING, "source $name not ready, retrying in $retry", e)
                Thread.sleep(retry)
            }
        }
    }

    private fun prepare(name: String) {
        val list = source.instruments(name)
        instruments.sync(name, list, clock.instant())
        val today = CandleUnit.DAY.floor(clock.instant())
        val from = minOf(CandleUnit.WEEK.floor(today), CandleUnit.MONTH.floor(today))
        for (info in list) {
            if (from < today) sync.syncWindow(info.id, CandleUnit.DAY, from, today, Priority.RECOVERY, audit = false)
            book.seedClosedDays(info.id, candles.findRange(info.id, CandleUnit.DAY, from, today))
        }
        source.tickers(name).forEach {
            tickers.put(it)
            book.onTicker(it)
        }
        // 꺼져 있던 동안은 장애 구간이다. 마지막 확인 구간 끝부터 열고, 연결 이벤트가 부르는 복구가 닫는다.
        if (outages != null && coverage != null) {
            val lastVerified = coverage.ends().filterKeys { it.first.source == name }.values.maxOrNull()
            outages.open(name, lastVerified ?: clock.instant())
        }
        tracker.update(name, source.streamStatus(name))
    }
}
