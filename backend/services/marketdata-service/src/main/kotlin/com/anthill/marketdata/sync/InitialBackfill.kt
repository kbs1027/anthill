package com.anthill.marketdata.sync

import com.anthill.contract.CandleUnit
import com.anthill.contract.Priority
import com.anthill.marketdata.candle.CandleSync
import com.anthill.marketdata.candle.CoverageRepository
import com.anthill.marketdata.candle.InstrumentRepository
import com.anthill.marketdata.live.TickerCache
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 남는 예산으로 과거를 채운다: 일·주·월봉은 상장일까지 전부, 분 단위는 최근 [minuteDays]일.
 * 이미 확인한 구간은 건너뛰므로 다시 돌려도 새 호출이 없다.
 */
class InitialBackfill(
    private val instruments: InstrumentRepository,
    private val coverage: CoverageRepository,
    private val sync: CandleSync,
    private val tickers: TickerCache,
    private val clock: Clock,
    private val minuteDays: Int,
) {
    private val log = Logger.getLogger(InitialBackfill::class.java.name)
    private val running = AtomicBoolean(false)

    fun startAsync() {
        if (!running.compareAndSet(false, true)) return
        Thread.ofVirtual().name("initial-backfill").start {
            try {
                runOnce()
            } catch (e: Exception) {
                log.log(Level.WARNING, "initial backfill stopped", e)
            } finally {
                running.set(false)
            }
        }
    }

    fun runOnce() {
        val now = clock.instant()
        val list = instruments.active().sortedByDescending { tickers.amount24h(it.id) ?: BigDecimal.ZERO }
        for (info in list) {
            for (u in listOf(CandleUnit.DAY, CandleUnit.WEEK, CandleUnit.MONTH)) {
                var cursor = coverage.start(info.id, u) ?: u.floor(now)
                while (cursor > Instant.EPOCH) {
                    if (sync.syncBefore(info.id, u, cursor, Priority.BACKFILL).reachedHistoryStart) break
                    cursor = coverage.start(info.id, u) ?: break
                }
            }
            for (u in CandleUnit.entries.filter { it.minutes != null }) {
                val from = u.floor(now.minus(Duration.ofDays(minuteDays.toLong())))
                val to = coverage.start(info.id, u) ?: u.floor(now)
                if (from < to) sync.syncWindow(info.id, u, from, to, Priority.BACKFILL, audit = false)
            }
        }
        log.info("initial backfill complete")
    }
}
