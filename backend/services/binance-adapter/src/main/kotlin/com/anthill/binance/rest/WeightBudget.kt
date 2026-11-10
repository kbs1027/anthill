package com.anthill.binance.rest

import com.anthill.contract.Priority
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 바이낸스 REST 분당 무게 예산. 최근 60초 동안 쓴 무게 + 이번 무게가 [limitPerMinute]를 넘지 않을 때만 내보내고,
 * 기다리는 요청은 우선순위(USER > RECOVERY > RECONCILE > BACKFILL), 같으면 도착 순서로 내보낸다.
 * 바이낸스가 그만 부르라고 하면(429·418, 무게가 한도 가까이 참) [pauseFor]로 모든 요청을 잠시 멈춘다.
 */
class WeightBudget(private val limitPerMinute: Int, private val timeSource: TimeSource = TimeSource.Monotonic) {

    private data class Ticket(val priority: Priority, val seq: Long)
    private data class Grant(val at: TimeMark, val weight: Int)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val grants = ArrayDeque<Grant>()
    private val queue = PriorityQueue(compareBy<Ticket>({ it.priority.ordinal }, { it.seq }))
    private var seq = 0L
    private var pausedUntil: TimeMark? = null

    /** 지금 차례를 기다리는 요청 수. */
    val waiting: Int get() = lock.withLock { queue.size }

    /** 최근 60초 동안 내보낸 무게 합. */
    val used: Int get() = lock.withLock { dropOld(); grants.sumOf { it.weight } }

    fun <T> run(priority: Priority, weight: Int, block: () -> T): T {
        acquire(priority, weight)
        return block()
    }

    /** 지금부터 [d] 동안 모든 요청을 멈춘다. 이미 더 길게 멈춰 있으면 그대로 둔다. */
    fun pauseFor(d: Duration) = lock.withLock {
        val remaining = pausedUntil?.let { -it.elapsedNow() } ?: Duration.ZERO
        if (d > remaining) pausedUntil = timeSource.markNow() + d
        changed.signalAll()
    }

    private fun dropOld() {
        while (grants.isNotEmpty() && grants.first().at.elapsedNow() >= 60.seconds) grants.removeFirst()
    }

    private fun acquire(priority: Priority, weight: Int) = lock.withLock {
        require(weight in 1..limitPerMinute) { "무게는 1..$limitPerMinute 이어야 한다: $weight" }
        val ticket = Ticket(priority, seq++)
        queue.add(ticket)
        try {
            while (true) {
                dropOld()
                val paused = pausedUntil?.hasNotPassedNow() == true
                if (!paused && queue.peek() == ticket && grants.sumOf { it.weight } + weight <= limitPerMinute) {
                    queue.poll()
                    grants.addLast(Grant(timeSource.markNow(), weight))
                    changed.signalAll()
                    return@withLock
                }
                changed.await(5, TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            queue.remove(ticket)
            changed.signalAll()
            throw e
        }
    }
}
