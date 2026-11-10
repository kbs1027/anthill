package com.anthill.marketdata.live

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.Priority
import com.anthill.contract.SecondCandle
import com.anthill.marketdata.candle.CandleSync
import com.anthill.marketdata.source.SourceClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 1초 캔들이 끊긴 종목의 진행 중 칸을 소스 REST로 다시 맞춘다(marketdata가 새로 시작할 때 포함).
 * 끊긴 뒤 첫 초를 `cut`이라 하면, 4시간 칸 시작부터 `cut`이 속한 분 시작까지는 1분봉, 그 분 시작부터 `cut` 직전까지는 1초 캔들로 받아
 * [apply](보통 [LiveCandleBook.applyRebuild])에 넘긴다.
 * - 종목마다 기다리는 요청은 하나뿐이다. 기다리는 동안 새 `cut`이 오면 그것으로 바꾼다. 같은 종목을 동시에 두 번 돌리지 않는다.
 * - 동시에 [parallelism]개 종목까지 요청한다.
 * - 실패하면 [retryDelay] 뒤 한 번 더 하고, 그래도 실패하면 로그만 남긴다(잘못 마감된 칸은 정합성 검사가 고친다).
 */
class SecondRebuilder(
    private val source: SourceClient,
    private val apply: (InstrumentId, Instant, List<Candle>, List<SecondCandle>) -> Unit,
    parallelism: Int = 4,
    private val retryDelay: Duration = Duration.ofSeconds(5),
    private val executor: Executor = Executors.newVirtualThreadPerTaskExecutor(),
) {
    private val log = Logger.getLogger(SecondRebuilder::class.java.name)
    private val permits = Semaphore(parallelism)
    private val lock = Any()
    private val waiting = HashMap<InstrumentId, Instant>()
    private val running = HashSet<InstrumentId>()

    fun request(i: InstrumentId, cut: Instant) {
        val launch = synchronized(lock) {
            val isNew = waiting.put(i, cut) == null
            isNew && i !in running
        }
        if (launch) executor.execute { work(i) }
    }

    private fun work(i: InstrumentId) {
        val cut = synchronized(lock) { waiting.remove(i)?.also { running += i } } ?: return
        try {
            if (!attempt(i, cut)) {
                Thread.sleep(retryDelay)
                // 기다리는 사이 더 새 끊김이 왔으면 그쪽이 다시 받는다.
                if (!newerWaiting(i) && !attempt(i, cut)) log.warning("진행 중 캔들 다시 맞추기 실패, 정합성 검사에 맡긴다: $i (끊긴 시각 $cut)")
            }
        } finally {
            val again = synchronized(lock) {
                running -= i
                i in waiting
            }
            if (again) executor.execute { work(i) }
        }
    }

    private fun newerWaiting(i: InstrumentId) = synchronized(lock) { i in waiting }

    private fun attempt(i: InstrumentId, cut: Instant): Boolean {
        permits.acquire()
        try {
            val minuteStart = CandleUnit.M1.floor(cut)
            val minutes = minutes(i, CandleUnit.M240.floor(cut), minuteStart)
            val seconds = if (minuteStart < cut) source.seconds(i, minuteStart, cut, Priority.RECOVERY) else emptyList()
            apply(i, cut, minutes, seconds)
            return true
        } catch (e: Exception) {
            log.log(Level.FINE, "다시 맞추기 요청 실패: $i (끊긴 시각 $cut)", e)
            return false
        } finally {
            permits.release()
        }
    }

    /** [from, to)의 1분봉을 과거→최신 순으로. 4시간 칸이라 최대 240개(요청 1~2번). */
    private fun minutes(i: InstrumentId, from: Instant, to: Instant): List<Candle> {
        if (from >= to) return emptyList()
        val out = ArrayList<Candle>()
        var cursor = to
        while (true) {
            val page = source.candles(i, CandleUnit.M1, cursor, CandleSync.PAGE, Priority.RECOVERY)
            page.filterTo(out) { it.start >= from && it.start < to }
            if (page.size < CandleSync.PAGE || page.last().start <= from) break
            cursor = page.last().start
        }
        return out.sortedBy { it.start }
    }
}
