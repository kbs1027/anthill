package com.anthill.binance.rest

import com.anthill.contract.Priority
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class WeightBudgetTest {

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(2)
        check(cond()) { "condition not met" }
    }

    @Test
    fun `never exceeds weight per minute`() {
        val time = TestTimeSource()
        val budget = WeightBudget(10, time)
        val start = time.markNow()
        val grants = Collections.synchronizedList(mutableListOf<Long>())
        val threads = (1..12).map { Thread.ofVirtual().start { budget.run(Priority.BACKFILL, 2) { grants += start.elapsedNow().inWholeSeconds } } }
        waitUntil { grants.size == 5 }
        Thread.sleep(30)
        assertEquals(5, grants.size, "무게 10이면 무게 2짜리는 5개까지만")
        time += 30.seconds
        Thread.sleep(30)
        assertEquals(5, grants.size, "60초가 지나기 전에는 더 못 나간다")
        time += 30.seconds
        waitUntil { grants.size == 10 }
        time += 60.seconds
        threads.forEach { it.join(2000) }
        assertEquals(12, grants.size)
        val sorted = grants.sorted()
        sorted.forEach { t -> assertTrue(sorted.count { it in t until t + 60 } * 2 <= 10, "window at $t: $sorted") }
    }

    @Test
    fun `higher priority goes first`() {
        val time = TestTimeSource()
        val budget = WeightBudget(10, time)
        budget.run(Priority.BACKFILL, 10) {}
        val order = Collections.synchronizedList(mutableListOf<Priority>())
        val waiting = mutableListOf<Thread>()
        listOf(Priority.BACKFILL, Priority.RECONCILE, Priority.BACKFILL, Priority.USER).forEach { p ->
            waiting += Thread.ofVirtual().start { budget.run(p, 2) { order += p } }
            val expected = waiting.size
            waitUntil { budget.waiting == expected }
        }
        time += 60.seconds
        waiting.forEach { it.join(2000) }
        assertEquals(listOf(Priority.USER, Priority.RECONCILE, Priority.BACKFILL, Priority.BACKFILL), order)
    }

    @Test
    fun `pause holds every request until it ends`() {
        val time = TestTimeSource()
        val budget = WeightBudget(100, time)
        budget.pauseFor(5.seconds)
        val done = Collections.synchronizedList(mutableListOf<Int>())
        val t = Thread.ofVirtual().start { budget.run(Priority.USER, 1) { done += 1 } }
        waitUntil { budget.waiting == 1 }
        time += 4.seconds
        Thread.sleep(30)
        assertEquals(0, done.size)
        budget.pauseFor(1.seconds) // 더 짧은 멈춤은 기존 멈춤을 줄이지 않는다
        time += 1.seconds
        t.join(2000)
        assertEquals(1, done.size)
    }
}
