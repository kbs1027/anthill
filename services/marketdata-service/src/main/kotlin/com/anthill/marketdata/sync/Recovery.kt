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
) {
    private val log = Logger.getLogger(Recovery::class.java.name)
    private val running = ConcurrentHashMap.newKeySet<String>()

    fun recoverAll(source: String) {
        if (!running.add(source)) return
        try {
            reconciler.pause()
            val now = clock.instant()
            for (info in instruments.active().filter { it.id.source == source }) {
                for (u in CandleUnit.entries) {
                    val to = u.floor(now)
                    val end = coverage.end(info.id, u)
                    try {
                        when {
                            end == null -> sync.syncBefore(info.id, u, to, Priority.RECOVERY, settledBefore = CandleSync.settledBefore(u, now))
                            end < to -> sync.syncWindow(info.id, u, end, to, Priority.RECOVERY, audit = true, settledBefore = CandleSync.settledBefore(u, now))
                        }
                    } catch (e: Exception) {
                        log.log(Level.WARNING, "recovery failed for ${info.id} $u", e)
                    }
                }
            }
            outages.close(source, clock.instant())
        } finally {
            reconciler.resume()
            running.remove(source)
        }
        backfill.startAsync()
    }
}
