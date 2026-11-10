package com.anthill.marketdata.sync

import com.anthill.contract.CandleUnit
import com.anthill.contract.Priority
import com.anthill.marketdata.candle.CandleSync
import com.anthill.marketdata.candle.CoverageRepository
import com.anthill.marketdata.candle.InstrumentRepository
import com.anthill.marketdata.live.TickerCache
import com.anthill.marketdata.live.ViewingTracker
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 정기 정합성 검사. 마감된 캔들을 소스 REST로 다시 받아 맞추고, 확인 구간을 앞으로 민다.
 * 확인 구간이 아예 없는 거래 중 (종목, 단위)(운영 중 새 상장 등)도 처음 200개를 받아 검사 대상에 넣는다.
 * 소스마다 따로 돈다([runDue]에 소스를 준다): 그 소스 종목만, 그 소스 장애 구간만 보고, 멈춤도 소스별이다.
 * 한 소스 안에서는 [parallelism]에 적힌 수만큼 동시에 처리한다(없으면 1).
 * 지표(모두 source·unit 태그): anthill.reconcile.overdue(장애 구간 제외, 순간값), anthill.reconcile.lag.seconds,
 * anthill.reconcile.late(검사 시한을 넘겨 확인한 구간 수, 장애 구간 제외, 누적 — 24시간 판정에 쓴다).
 */
class Reconciler(
    private val planner: ReconcilePlanner,
    private val sync: CandleSync,
    private val coverage: CoverageRepository,
    private val instruments: InstrumentRepository,
    private val tickers: TickerCache,
    private val viewing: ViewingTracker,
    private val outages: OutageLog,
    private val meter: MeterRegistry,
    private val clock: Clock,
    private val parallelism: Map<String, Int> = emptyMap(),
) {
    private val log = Logger.getLogger(Reconciler::class.java.name)
    private val overdueGauges = ConcurrentHashMap<Pair<String, CandleUnit>, AtomicLong>()
    private val lagGauges = ConcurrentHashMap<Pair<String, CandleUnit>, AtomicLong>()
    private val pausedSources = ConcurrentHashMap.newKeySet<String>()

    fun pause(source: String) { pausedSources += source }

    fun resume(source: String) { pausedSources -= source }

    fun isPaused(source: String) = source in pausedSources

    fun runDue(source: String) {
        if (isPaused(source)) return
        val now = clock.instant()
        val windows = outages.windows(Instant.EPOCH, now, source)
        val ends = activeEnds(source)
        val tasks = ArrayList<() -> Unit>()
        for (job in planner.due(now, ends, viewing.viewed(), tickers::amount24h)) {
            tasks += {
                try {
                    sync.syncWindow(job.instrument, job.unit, job.from, job.to, Priority.RECONCILE, audit = true, settledBefore = CandleSync.settledBefore(job.unit, now))
                    val close = job.unit.next(job.from)
                    if (now.isAfter(close.plus(job.unit.reconcileDeadline)) && windows.none { (f, t) -> !close.isBefore(f) && !close.isAfter(t) }) {
                        meter.counter("anthill.reconcile.late", "source", source, "unit", job.unit.code).increment()
                    }
                } catch (e: Exception) {
                    log.log(Level.WARNING, "reconcile failed for $job", e)
                }
            }
        }
        for (info in instruments.active().filter { it.id.source == source }) {
            for (u in CandleUnit.entries) {
                if ((info.id to u) in ends) continue
                tasks += {
                    try {
                        sync.syncBefore(info.id, u, u.floor(now), Priority.RECONCILE, settledBefore = CandleSync.settledBefore(u, now))
                    } catch (e: Exception) {
                        log.log(Level.WARNING, "first sync failed for ${info.id} $u", e)
                    }
                }
            }
        }
        runAll(source, tasks)
        val after = clock.instant()
        overdue(source, after).forEach { (u, n) -> gauge(overdueGauges, "anthill.reconcile.overdue", source, u).set(n.toLong()) }
        lag(source, after).forEach { (u, d) -> gauge(lagGauges, "anthill.reconcile.lag.seconds", source, u).set(d.seconds) }
    }

    /** [tasks]를 앞에서부터 꺼내 [parallelism]개씩 동시에 처리한다. 멈추면 남은 것은 하지 않는다. */
    private fun runAll(source: String, tasks: List<() -> Unit>) {
        val n = (parallelism[source] ?: 1).coerceAtLeast(1)
        val queue = ConcurrentLinkedQueue(tasks)
        val worker = Runnable {
            while (!isPaused(source)) {
                val task = queue.poll() ?: break
                task()
            }
        }
        if (n == 1) return worker.run()
        (1..minOf(n, tasks.size)).map { Thread.ofVirtual().name("reconcile-$source-$it").start(worker) }.forEach { it.join() }
    }

    /** [source]에서 검사 시한을 넘긴 (종목, 단위) 수. 첫 미검증 캔들의 마감 시각이 그 소스 장애 구간 안이면 세지 않는다. */
    fun overdue(source: String, now: Instant): Map<CandleUnit, Int> {
        val windows = outages.windows(Instant.EPOCH, now, source)
        return activeEnds(source).entries
            .filter { (key, end) ->
                val close = key.second.next(end)
                now.isAfter(close.plus(key.second.reconcileDeadline)) && windows.none { (f, t) -> !close.isBefore(f) && !close.isAfter(t) }
            }
            .groupingBy { it.key.second }.eachCount()
    }

    private fun lag(source: String, now: Instant): Map<CandleUnit, Duration> =
        activeEnds(source).entries.groupBy({ it.key.second }) { Duration.between(it.key.second.next(it.value), now).coerceAtLeast(Duration.ZERO) }
            .mapValues { (_, v) -> v.max() }

    private fun gauge(cache: ConcurrentHashMap<Pair<String, CandleUnit>, AtomicLong>, name: String, source: String, u: CandleUnit) =
        cache.computeIfAbsent(source to u) { meter.gauge(name, listOf(Tag.of("source", source), Tag.of("unit", u.code)), AtomicLong())!! }

    private fun activeEnds(source: String) =
        instruments.active().map { it.id }.filter { it.source == source }.toSet().let { active -> coverage.ends().filterKeys { it.first in active } }
}
