package com.anthill.marketdata.candle

import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.marketdata.candle.CandleRepository.Companion.utc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 소스 REST로 확인을 끝낸 구간. DB에 캔들이 없을 때 "거래 없음"과 "아직 안 받음"을 가르는 근거다.
 * 겹치거나 맞닿은 구간은 하나로 합친다.
 */
@Repository
class CoverageRepository(private val jdbc: JdbcClient) {

    @Transactional
    fun add(i: InstrumentId, u: CandleUnit, from: Instant, to: Instant) {
        require(from < to) { "empty coverage [$from, $to)" }
        // 같은 (종목, 단위)는 한 번에 하나씩 고친다. 아래 for update는 이미 있는 행만 잠그므로,
        // 행이 아직 없을 때 두 요청이 동시에 들어오면 둘 다 insert해서 기본 키가 부딪친다.
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:key))").param("key", "coverage:${i.value}:${u.code}").query().singleValue()
        val touching = jdbc.sql(
            "select from_at, to_at from coverage where instrument_id = :i and unit = :u and from_at <= :to and to_at >= :from for update",
        ).param("i", i.value).param("u", u.code).param("from", utc(from)).param("to", utc(to))
            .query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant() to rs.getObject(2, OffsetDateTime::class.java).toInstant() }
            .list()
        val mergedFrom = (touching.map { it.first } + from).min()
        val mergedTo = (touching.map { it.second } + to).max()
        jdbc.sql("delete from coverage where instrument_id = :i and unit = :u and from_at <= :to and to_at >= :from")
            .param("i", i.value).param("u", u.code).param("from", utc(from)).param("to", utc(to)).update()
        jdbc.sql("insert into coverage (instrument_id, unit, from_at, to_at) values (:i, :u, :from, :to)")
            .param("i", i.value).param("u", u.code).param("from", utc(mergedFrom)).param("to", utc(mergedTo)).update()
    }

    /** from < end ≤ to 인 구간의 시작. 없으면 null. */
    fun coveredStartFor(i: InstrumentId, u: CandleUnit, end: Instant): Instant? =
        jdbc.sql("select from_at from coverage where instrument_id = :i and unit = :u and from_at < :end and to_at >= :end")
            .param("i", i.value).param("u", u.code).param("end", utc(end))
            .query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant() }.optional().orElse(null)

    fun end(i: InstrumentId, u: CandleUnit): Instant? = aggregate("max(to_at)", i, u)

    fun start(i: InstrumentId, u: CandleUnit): Instant? = aggregate("min(from_at)", i, u)

    /** (종목, 단위) → 확인 구간 끝. 정합성 검사 계획에 쓴다. */
    fun ends(): Map<Pair<InstrumentId, CandleUnit>, Instant> =
        jdbc.sql("select instrument_id, unit, max(to_at) from coverage group by instrument_id, unit")
            .query { rs, _ ->
                (InstrumentId.parse(rs.getString(1)) to CandleUnit.fromCode(rs.getString(2))!!) to rs.getObject(3, OffsetDateTime::class.java).toInstant()
            }.list().toMap()

    private fun aggregate(expr: String, i: InstrumentId, u: CandleUnit): Instant? =
        jdbc.sql("select $expr from coverage where instrument_id = :i and unit = :u")
            .param("i", i.value).param("u", u.code)
            .query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java)?.toInstant() }.optional().orElse(null)
}
