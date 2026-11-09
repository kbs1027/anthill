package com.anthill.upbit.rest

import com.anthill.contract.Priority
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class RequestBudgetTest {

    @Test
    fun `budget never exceeds rate`() {
        val time = TestTimeSource()
        val budget = RequestBudget(9, time)
        val start = time.markNow()
        val grants = Collections.synchronizedList(mutableListOf<Long>())
        val threads = (1..20).map { Thread.ofVirtual().start { budget.run(Priority.BACKFILL) { grants += start.elapsedNow().inWholeMilliseconds } } }
        repeat(40) {
            Thread.sleep(15)
            time += 100.milliseconds
        }
        threads.forEach { it.join(2000) }
        assertEquals(20, grants.size)
        val sorted = grants.sorted()
        sorted.forEach { t -> assertTrue(sorted.count { it in t until t + 1000 } <= 9, "window at $t: $sorted") }
    }

    @Test
    fun `higher priority goes first`() {
        val time = TestTimeSource()
        val budget = RequestBudget(9, time)
        repeat(9) { budget.run(Priority.BACKFILL) {} }
        val order = Collections.synchronizedList(mutableListOf<Priority>())
        val waiting = mutableListOf<Thread>()
        listOf(Priority.BACKFILL, Priority.BACKFILL, Priority.BACKFILL, Priority.USER).forEach { p ->
            waiting += Thread.ofVirtual().start { budget.run(p) { order += p } }
            val expected = waiting.size
            while (budget.waiting < expected) Thread.sleep(2)
        }
        time += 1.seconds
        waiting.forEach { it.join(2000) }
        assertEquals(Priority.USER, order.first())
        assertEquals(4, order.size)
    }
}
