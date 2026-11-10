package com.anthill.marketdata.sync

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 소스마다 정합성 검사 반복을 하나씩 둔다. 한 소스가 느리거나 멈춰도 다른 소스 검사는 제때 돈다.
 * 소스마다 가상 스레드 하나짜리 스케줄러에서 앞 회차가 끝난 뒤 [delay]만큼 쉬고 다시 돈다. 닫으면 모두 멈춘다.
 */
class ReconcileLoops(
    private val sources: Collection<String>,
    private val reconciler: Reconciler,
    private val delay: Duration = Duration.ofSeconds(5),
) : AutoCloseable {
    private val log = Logger.getLogger(ReconcileLoops::class.java.name)
    private val schedulers = ArrayList<ScheduledExecutorService>()

    fun start() {
        for (source in sources) {
            val scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("reconcile-$source").factory())
            // 예외가 밖으로 나가면 스케줄러가 반복을 멈추므로 여기서 잡는다.
            val task = Runnable {
                try {
                    reconciler.runDue(source)
                } catch (e: Exception) {
                    log.log(Level.WARNING, "정합성 검사 회차 실패: $source", e)
                }
            }
            scheduler.scheduleWithFixedDelay(task, delay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS)
            schedulers += scheduler
        }
    }

    override fun close() {
        schedulers.forEach { it.shutdownNow() }
    }
}
