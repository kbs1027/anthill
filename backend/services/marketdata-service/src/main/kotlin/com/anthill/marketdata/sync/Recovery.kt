package com.anthill.marketdata.sync

import com.anthill.contract.CandleUnit
import com.anthill.contract.Priority
import com.anthill.marketdata.candle.CandleSync
import com.anthill.marketdata.candle.CoverageRepository
import com.anthill.marketdata.candle.InstrumentRepository
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 끊긴 사이의 빈 구간을 채운다. 소스 스트림이 다시 연결되거나 marketdata가 시작할 때 부른다.
 * 도는 동안 정기 검사를 멈추고 예산을 복구에 몰아준다. 같은 소스에 대해 동시에 두 번 돌지 않는다.
 */
class Recovery(
    private val instruments: InstrumentRepository,
    private val coverage: CoverageRepository,
    private val sync: CandleSync,
    private val reconciler: Reconciler,
    private val backfill: InitialBackfill,
    private val outages: OutageLog,
    private val clock: Clock,
    private val retryDelay: java.time.Duration = java.time.Duration.ofSeconds(15),
    private val maxPasses: Int = 10,
) {
    private val log = Logger.getLogger(Recovery::class.java.name)
    private val running = ConcurrentHashMap.newKeySet<String>()

    /**
     * 확인 구간 끝부터 지금까지를 채운다. 실패가 하나라도 있으면 장애 구간을 닫지 않고, 정기 검사를 잠시 돌려준 뒤
     * [retryDelay] 후 남은 것만 다시 한다([maxPasses]번까지). 그래도 남으면 장애 구간을 닫고 정기 검사에 맡긴다
     * (거래 정지된 종목 하나 때문에 정기 검사가 영영 멈추지 않도록).
     */
    fun recoverAll(source: String) {
        if (!running.add(source)) return
        try {
            for (pass in 1..maxPasses) {
                val failures = try {
                    reconciler.pause()
                    recoverPass(source)
                } finally {
                    reconciler.resume()
                }
                if (failures == 0) break
                if (pass == maxPasses) {
                    log.warning("recovery for $source left $failures (instrument, unit) pairs to the reconciler after $maxPasses passes")
                    break
                }
                log.warning("recovery pass $pass for $source had $failures failures, retrying in $retryDelay")
                Thread.sleep(retryDelay)
            }
            outages.close(source, clock.instant())
        } finally {
            running.remove(source)
        }
        backfill.startAsync()
    }

    /** 한 바퀴. 이미 정착 시점까지 확인한 (종목, 단위)는 건너뛴다. 실패한 수를 돌려준다. */
    private fun recoverPass(source: String): Int {
        val now = clock.instant()
        var failures = 0
        for (info in instruments.active().filter { it.id.source == source }) {
            for (u in CandleUnit.entries) {
                val to = u.floor(now)
                val settled = CandleSync.settledBefore(u, now)
                val end = coverage.end(info.id, u)
                try {
                    when {
                        end == null -> sync.syncBefore(info.id, u, to, Priority.RECOVERY, settledBefore = settled)
                        end < settled -> sync.syncWindow(info.id, u, end, to, Priority.RECOVERY, audit = true, settledBefore = settled)
                    }
                } catch (e: Exception) {
                    failures++
                    log.log(Level.FINE, "recovery failed for ${info.id} $u", e)
                }
            }
        }
        return failures
    }
}
