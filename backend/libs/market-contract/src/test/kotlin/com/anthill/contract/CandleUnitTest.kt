package com.anthill.contract

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class CandleUnitTest {

    @Test
    fun `minute units floor to UTC aligned buckets`() {
        assertEquals(Instant.parse("2026-11-04T10:32:00Z"), CandleUnit.M1.floor(Instant.parse("2026-11-04T10:32:59.999Z")))
        assertEquals(Instant.parse("2026-11-04T10:30:00Z"), CandleUnit.M15.floor(Instant.parse("2026-11-04T10:44:00Z")))
    }

    @Test
    fun `240m aligns to UTC 0-4-8`() =
        assertEquals(Instant.parse("2026-11-04T04:00:00Z"), CandleUnit.M240.floor(Instant.parse("2026-11-04T07:59:59Z")))

    @Test
    fun `day starts 00 UTC`() =
        assertEquals(Instant.parse("2026-11-05T00:00:00Z"), CandleUnit.DAY.floor(Instant.parse("2026-11-05T14:59:00Z")))

    @Test
    fun `week starts Monday 00 UTC`() {
        assertEquals(Instant.parse("2026-11-02T00:00:00Z"), CandleUnit.WEEK.floor(Instant.parse("2026-11-08T23:59:59.999Z")))
        assertEquals(Instant.parse("2026-11-09T00:00:00Z"), CandleUnit.WEEK.floor(Instant.parse("2026-11-09T00:00:00Z")))
    }

    @Test
    fun `month floor and next`() {
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), CandleUnit.MONTH.floor(Instant.parse("2026-02-28T23:59:59Z")))
        assertEquals(Instant.parse("2026-03-01T00:00:00Z"), CandleUnit.MONTH.next(Instant.parse("2026-02-01T00:00:00Z")))
    }

    @Test
    fun `next advances one period`() {
        assertEquals(Instant.parse("2026-11-04T08:00:00Z"), CandleUnit.M240.next(Instant.parse("2026-11-04T04:00:00Z")))
        assertEquals(Instant.parse("2026-11-09T00:00:00Z"), CandleUnit.WEEK.next(Instant.parse("2026-11-02T00:00:00Z")))
    }

    @Test
    fun `reconcile slack and deadline follow spec`() {
        assertEquals(Duration.ofMinutes(3), CandleUnit.M5.reconcileSlack)
        assertEquals(Duration.ofMinutes(5), CandleUnit.M1.reconcileDeadline)
        assertEquals(Duration.ofMinutes(5), CandleUnit.M10.reconcileSlack)
        assertEquals(Duration.ofMinutes(10), CandleUnit.M10.reconcileDeadline)
        assertEquals(Duration.ofDays(15), CandleUnit.MONTH.reconcileSlack)
        assertEquals(Duration.ofDays(31), CandleUnit.MONTH.reconcileDeadline)
    }

    @Test
    fun `codes round trip`() = CandleUnit.entries.forEach { assertEquals(it, CandleUnit.fromCode(it.code)) }
}
