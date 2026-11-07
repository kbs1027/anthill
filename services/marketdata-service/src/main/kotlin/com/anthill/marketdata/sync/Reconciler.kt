package com.anthill.marketdata.sync

import com.anthill.contract.CandleUnit
import com.anthill.contract.Priority
import com.anthill.marketdata.candle.CandleSync
import com.anthill.marketdata.candle.CoverageRepository
import com.anthill.marketdata.candle.InstrumentRepository
import com.anthill.marketdata.live.TickerCache
import com.anthill.marketdata.live.ViewingTracker
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 정기 정합성 검사. 마감된 캔들을 소스 REST로 다시 받아 맞추고, 확인 구간을 앞으로 민다.
 * 지표: anthill.reconcile.overdue{unit}(장애 구간 제외), anthill.reconcile.lag.seconds{unit}.
 */
class Reconciler(
    private val planner: ReconcilePlanner,
    private val sync: CandleSync,
    private val coverage: CoverageRepository,
    private val instruments: InstrumentRepository,
    private val tickers: TickerCache,
    private val viewing: ViewingTracker,
    private val outages: OutageLog,
    meter: MeterRegistry,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(Reconciler::class.java.name)
    private val overdueGauges = CandleUnit.entries.associateWith { u -> meter.gauge("anthill.reconcile.overdue", listOf(io.micrometer.core.instrument.Tag.of("unit", u.code)), AtomicLong())!! }
    private val lagGauges = CandleUnit.entries.associateWith { u -> meter.gauge("anthill.reconcile.lag.seconds", listOf(io.micrometer.core.instrument.Tag.of("unit", u.code)), AtomicLong())!! }

    @Volatile var paused = false
        private set

    fun pause() { paused = true }

    fun resume() { paused = false }

    fun runDue() {
        if (paused) return
        val now = clock.instant()
        for (job in planner.due(now, activeEnds(), viewing.viewed(), tickers::amount24h)) {
            if (paused) break
            try {
                sync.syncWindow(job.instrument, job.unit, job.from, job.to, Priority.RECONCILE, audit = true)
            } catch (e: Exception) {
                log.log(Level.WARNING, "reconcile failed for $job", e)
            }
        }
        val after = clock.instant()
        overdue(after).forEach { (u, n) -> overdueGauges.getValue(u).set(n.toLong()) }
        lag(after).forEach { (u, d) -> lagGauges.getValue(u).set(d.seconds) }
    }

    /** 검사 시한을 넘긴 (종목, 단위) 수. 첫 미검증 캔들의 마감 시각이 장애 구간 안이면 세지 않는다. */
    fun overdue(now: Instant): Map<CandleUnit, Int> {
        val windows = outages.windows(Instant.EPOCH, now)
        return activeEnds().entries
            .filter { (key, end) ->
                val close = key.second.next(end)
                now.isAfter(close.plus(key.second.reconcileDeadline)) && windows.none { (f, t) -> !close.isBefore(f) && !close.isAfter(t) }
            }
            .groupingBy { it.key.second }.eachCount()
    }

    private fun lag(now: Instant): Map<CandleUnit, Duration> =
        activeEnds().entries.groupBy({ it.key.second }) { Duration.between(it.key.second.next(it.value), now).coerceAtLeast(Duration.ZERO) }
            .mapValues { (_, v) -> v.max() }

    private fun activeEnds() = instruments.active().map { it.id }.toSet().let { active -> coverage.ends().filterKeys { it.first in active } }
}
