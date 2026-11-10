package com.anthill.contract

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** 캔들 단위. 경계는 업비트·바이낸스와 같은 UTC 기준(일봉 00:00, 주봉 월요일 00:00, 월봉 1일 00:00). */
enum class CandleUnit(val code: String, val minutes: Int?) {
    M1("1m", 1), M3("3m", 3), M5("5m", 5), M10("10m", 10), M15("15m", 15),
    M30("30m", 30), M60("60m", 60), M240("240m", 240),
    DAY("day", null), WEEK("week", null), MONTH("month", null);

    fun floor(t: Instant): Instant {
        minutes?.let {
            val size = it * 60L
            return Instant.ofEpochSecond(Math.floorDiv(t.epochSecond, size) * size)
        }
        val day = LocalDate.ofInstant(t, ZoneOffset.UTC)
        val start = when (this) {
            DAY -> day
            WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            else -> day.withDayOfMonth(1)
        }
        return start.atStartOfDay(ZoneOffset.UTC).toInstant()
    }

    fun next(start: Instant): Instant {
        minutes?.let { return start.plus(it.toLong(), ChronoUnit.MINUTES) }
        val day = LocalDate.ofInstant(start, ZoneOffset.UTC)
        val next = when (this) {
            DAY -> day.plusDays(1)
            WEEK -> day.plusWeeks(1)
            else -> day.plusMonths(1)
        }
        return next.atStartOfDay(ZoneOffset.UTC).toInstant()
    }

    private val period: Duration
        get() = minutes?.let { Duration.ofMinutes(it.toLong()) } ?: when (this) {
            DAY -> Duration.ofDays(1)
            WEEK -> Duration.ofDays(7)
            else -> Duration.ofDays(31)
        }

    /** 마감 뒤 이만큼 지나면 정합성 검사 대상이 된다. */
    val reconcileSlack: Duration
        get() = if (this in SHORT) Duration.ofMinutes(3) else if (this == MONTH) Duration.ofDays(15) else period.dividedBy(2)

    /** 마감 뒤 이 안에 검사를 마쳐야 한다. */
    val reconcileDeadline: Duration
        get() = if (this in SHORT) Duration.ofMinutes(5) else period

    companion object {
        private val SHORT = setOf(M1, M3, M5)
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: String): CandleUnit? = byCode[code]
    }
}
