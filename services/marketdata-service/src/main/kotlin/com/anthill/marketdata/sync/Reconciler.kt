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
 * 확인 구간이 아예 없는 거래 중 (종목, 단위)(운영 중 새 상장 등)도 처음 200개를 받아 검사 대상에 넣는다.
 * 지표: anthill.reconcile.overdue{unit}(장애 구간 제외, 순간값), anthill.reconcile.lag.seconds{unit},
 * anthill.reconcile.late{unit}(검사 시한을 넘겨 확인한 구간 수, 장애 구간 제외, 누적 — 24시간 판정에 쓴다).
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
    private val lateCounters = CandleUnit.entries.associateWith { u -> meter.counter("anthill.reconcile.late", "unit", u.code) }

    @Volatile var paused = false
        private set

    fun pause() { paused = true }

    fun resume() { paused = false }

    fun runDue() {
        if (paused) return
        val now = clock.instant()
        val windows = outages.windows(Instant.EPOCH, now)
        val ends = activeEnds()
        for (job in planner.due(now, ends, viewing.viewed(), tickers::amount24h)) {
            if (paused) break
            try {
                sync.syncWindow(job.instrument, job.unit, job.from, job.to, Priority.RECONCILE, audit = true, settledBefore = CandleSync.settledBefore(job.unit, now))
                val close = job.unit.next(job.from)
                if (now.isAfter(close.plus(job.unit.reconcileDeadline)) && windows.none { (f, t) -> !close.isBefore(f) && !close.isAfter(t) }) {
                    lateCounters.getValue(job.unit).increment()
                }
            } catch (e: Exception) {
                log.log(Level.WARNING, "reconcile failed for $job", e)
            }
        }
        for (info in instruments.active()) {
            for (u in CandleUnit.entries) {
                if (paused) break
                if ((info.id to u) in ends) continue
                try {
                    sync.syncBefore(info.id, u, u.floor(now), Priority.RECONCILE, settledBefore = CandleSync.settledBefore(u, now))
                } catch (e: Exception) {
                    log.log(Level.WARNING, "first sync failed for ${info.id} $u", e)
                }
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
