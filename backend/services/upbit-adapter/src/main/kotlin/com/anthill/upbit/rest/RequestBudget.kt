package com.anthill.upbit.rest

import com.anthill.contract.Priority
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 업비트 REST 요청 예산. 어떤 1초 구간에도 [ratePerSecond]회를 넘지 않게 하고,
 * 기다리는 요청은 우선순위(USER > RECOVERY > RECONCILE > BACKFILL), 같으면 도착 순서로 내보낸다.
 */
class RequestBudget(private val ratePerSecond: Int, private val timeSource: TimeSource = TimeSource.Monotonic) {

    private data class Ticket(val priority: Priority, val seq: Long)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val grants = ArrayDeque<TimeMark>()
    private val queue = PriorityQueue(compareBy<Ticket>({ it.priority.ordinal }, { it.seq }))
    private var seq = 0L

    /** 지금 차례를 기다리는 요청 수. */
    val waiting: Int get() = lock.withLock { queue.size }

    fun <T> run(priority: Priority, block: () -> T): T {
        acquire(priority)
        return block()
    }

    private fun acquire(priority: Priority) = lock.withLock {
        val ticket = Ticket(priority, seq++)
        queue.add(ticket)
        try {
            while (true) {
                while (grants.isNotEmpty() && grants.first().elapsedNow() >= 1.seconds) grants.removeFirst()
                if (queue.peek() == ticket && grants.size < ratePerSecond) {
                    queue.poll()
                    grants.addLast(timeSource.markNow())
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
