package com.anthill.marketdata.sync

import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcilePlannerTest {

    private val planner = ReconcilePlanner()
    private val a = InstrumentId.parse("UPBIT:KRW-AAA")
    private val b = InstrumentId.parse("UPBIT:KRW-BBB")
    private fun at(s: String) = Instant.parse(s)

    @Test
    fun `1m job due 3 minutes after first unverified close`() {
        val ends = mapOf((a to CandleUnit.M1) to at("2026-11-08T10:00:00Z"))
        assertTrue(planner.due(at("2026-11-08T10:03:59Z"), ends, emptySet()) { null }.isEmpty())
        val job = planner.due(at("2026-11-08T10:04:00Z"), ends, emptySet()) { null }.single()
        assertEquals(ReconcileJob(a, CandleUnit.M1, at("2026-11-08T10:00:00Z"), at("2026-11-08T10:04:00Z")), job)
    }

    @Test
    fun `10m job due half a period after close`() {
        val ends = mapOf((a to CandleUnit.M10) to at("2026-11-08T10:00:00Z"))
        assertTrue(planner.due(at("2026-11-08T10:14:59Z"), ends, emptySet()) { null }.isEmpty())
        assertEquals(1, planner.due(at("2026-11-08T10:15:00Z"), ends, emptySet()) { null }.size)
    }

    @Test
    fun `viewed instruments first then amount desc`() {
        val end = at("2026-11-08T10:00:00Z")
        val ends = mapOf((a to CandleUnit.M1) to end, (b to CandleUnit.M1) to end, (a to CandleUnit.M5) to end)
        val now = at("2026-11-08T10:30:00Z")
        val byAmount = planner.due(now, ends, emptySet()) { if (it == b) BigDecimal("900") else BigDecimal("1") }
        assertEquals(listOf(b, a, a), byAmount.map { it.instrument })
        val viewedFirst = planner.due(now, ends, setOf(a)) { if (it == b) BigDecimal("900") else BigDecimal("1") }
        assertEquals(listOf(a to CandleUnit.M1, a to CandleUnit.M5, b to CandleUnit.M1), viewedFirst.map { it.instrument to it.unit })
    }

    @Test
    fun `deadline holds at configured budget`() {
        val instruments = (1..299).map { InstrumentId.of("UPBIT", "KRW-T$it") }
        val start = at("2026-11-08T00:00:00Z")
        val ends = HashMap<Pair<InstrumentId, CandleUnit>, Instant>()
        instruments.forEach { i -> CandleUnit.entries.forEach { u -> ends[i to u] = u.floor(start) } }
        val worst = HashMap<CandleUnit, Duration>()
        var now = start
        val rate = 9
        while (now < start.plus(Duration.ofHours(1))) {
            val jobs = planner.due(now, ends, emptySet()) { null }
            jobs.take(rate * 5).forEachIndexed { idx, job ->
                val doneAt = now.plusMillis(idx * 1000L / rate)
                val delay = Duration.between(job.unit.next(job.from), doneAt)
                worst.merge(job.unit, delay) { x, y -> maxOf(x, y) }
                ends[job.instrument to job.unit] = job.to
            }
            now = now.plusSeconds(5)
        }
        for (u in CandleUnit.entries) {
            val w = worst[u] ?: continue
            assertTrue(w <= u.reconcileDeadline, "$u worst delay $w > ${u.reconcileDeadline}")
        }
        assertTrue(worst.containsKey(CandleUnit.M1))
    }
}
