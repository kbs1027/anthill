package com.anthill.marketdata.sync

import com.anthill.marketdata.candle.CandleRepository.Companion.utc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 장애 구간: 소스 스트림이 끊긴 시점부터 복구가 끝날 때까지. 이 구간에 마감된 캔들은 검사 시한 판정에서 뺀다(불일치 판정은 그대로).
 */
@Repository
class OutageLog(private val jdbc: JdbcClient) {

    fun open(source: String, from: Instant) {
        val opened = jdbc.sql("select count(*) from outage where source = :s and to_at is null").param("s", source).query(Long::class.java).single()
        if (opened == 0L) jdbc.sql("insert into outage (source, from_at) values (:s, :from)").param("s", source).param("from", utc(from)).update()
    }

    fun close(source: String, to: Instant) {
        jdbc.sql("update outage set to_at = :to where source = :s and to_at is null").param("s", source).param("to", utc(to)).update()
    }

    /** [from, to)와 겹치는 장애 구간. 아직 열려 있으면 끝을 [to]로 본다. [source]를 주면 그 소스 것만. */
    fun windows(from: Instant, to: Instant, source: String? = null): List<Pair<Instant, Instant>> =
        jdbc.sql(
            "select from_at, to_at from outage where from_at < :to and (to_at is null or to_at > :from)" +
                (if (source != null) " and source = :s" else "") + " order by from_at",
        )
            .param("from", utc(from)).param("to", utc(to))
            .let { if (source != null) it.param("s", source) else it }
            .query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant() to (rs.getObject(2, OffsetDateTime::class.java)?.toInstant() ?: to) }
            .list()
}
