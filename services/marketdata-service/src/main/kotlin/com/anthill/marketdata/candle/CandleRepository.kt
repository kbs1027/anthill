package com.anthill.marketdata.candle

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** 소스가 준 캔들을 단위별로 그대로 저장한다. 숫자는 NUMERIC, 시각은 timestamptz(UTC). */
@Repository
class CandleRepository(private val jdbc: JdbcClient, private val template: JdbcTemplate) {

    fun upsert(candles: Collection<Candle>) {
        if (candles.isEmpty()) return
        template.batchUpdate(
            """
            insert into candle (instrument_id, unit, start_at, open, high, low, close, volume, amount)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (instrument_id, unit, start_at) do update set open = excluded.open, high = excluded.high,
              low = excluded.low, close = excluded.close, volume = excluded.volume, amount = excluded.amount
            """.trimIndent(),
            candles.map { c -> arrayOf<Any>(c.instrument.value, c.unit.code, utc(c.start), c.open, c.high, c.low, c.close, c.volume, c.amount) },
        )
    }

    /** [from, to) 오름차순. */
    fun findRange(i: InstrumentId, u: CandleUnit, from: Instant, to: Instant): List<Candle> =
        jdbc.sql("$SELECT where instrument_id = :i and unit = :u and start_at >= :from and start_at < :to order by start_at")
            .param("i", i.value).param("u", u.code).param("from", utc(from)).param("to", utc(to))
            .query { rs, _ -> map(rs, i, u) }.list()

    /** start < to 인 것 중 최신 [limit]개를 오름차순으로. */
    fun findBefore(i: InstrumentId, u: CandleUnit, to: Instant, limit: Int): List<Candle> =
        jdbc.sql("$SELECT where instrument_id = :i and unit = :u and start_at < :to order by start_at desc limit :limit")
            .param("i", i.value).param("u", u.code).param("to", utc(to)).param("limit", limit)
            .query { rs, _ -> map(rs, i, u) }.list().reversed()

    fun delete(i: InstrumentId, u: CandleUnit, starts: Collection<Instant>) {
        if (starts.isEmpty()) return
        template.batchUpdate(
            "delete from candle where instrument_id = ? and unit = ? and start_at = ?",
            starts.map { arrayOf<Any>(i.value, u.code, utc(it)) },
        )
    }

    private fun map(rs: ResultSet, i: InstrumentId, u: CandleUnit) = Candle(
        i, u, rs.getObject("start_at", OffsetDateTime::class.java).toInstant(),
        rs.getBigDecimal("open"), rs.getBigDecimal("high"), rs.getBigDecimal("low"), rs.getBigDecimal("close"),
        rs.getBigDecimal("volume"), rs.getBigDecimal("amount"),
    )

    companion object {
        private const val SELECT = "select start_at, open, high, low, close, volume, amount from candle"
        fun utc(t: Instant): OffsetDateTime = OffsetDateTime.ofInstant(t, ZoneOffset.UTC)
    }
}
