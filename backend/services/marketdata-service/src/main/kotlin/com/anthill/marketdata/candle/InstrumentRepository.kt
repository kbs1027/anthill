package com.anthill.marketdata.candle

import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class InstrumentRepository(private val jdbc: JdbcClient) {

    /** 소스의 종목 목록과 맞춘다. 목록에 없는 종목은 active=false로만 바꾸고 데이터는 남긴다. 바뀐 게 있으면 true. */
    @Transactional
    fun sync(source: String, list: List<InstrumentInfo>, at: Instant): Boolean {
        val now = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
        var changed = 0
        for (info in list) {
            changed += jdbc.sql(
                """
                insert into instrument (id, source, symbol, korean_name, english_name, active, updated_at)
                values (:id, :source, :symbol, :ko, :en, true, :at)
                on conflict (id) do update set korean_name = excluded.korean_name, english_name = excluded.english_name,
                  active = true, updated_at = excluded.updated_at
                where instrument.korean_name <> excluded.korean_name or instrument.english_name <> excluded.english_name
                  or instrument.active = false
                """.trimIndent(),
            ).param("id", info.id.value).param("source", source).param("symbol", info.id.symbol)
                .param("ko", info.koreanName).param("en", info.englishName).param("at", now).update()
        }
        val ids = list.map { it.id.value }.toTypedArray()
        changed += jdbc.sql("update instrument set active = false, updated_at = :at where source = :source and active and not (id = any(:ids))")
            .param("at", now).param("source", source).param("ids", ids).update()
        return changed > 0
    }

    fun active(): List<InstrumentInfo> =
        jdbc.sql("select id, korean_name, english_name from instrument where active order by id")
            .query { rs, _ -> InstrumentInfo(InstrumentId.parse(rs.getString(1)), rs.getString(2), rs.getString(3)) }
            .list()

    fun exists(id: InstrumentId): Boolean =
        jdbc.sql("select count(*) from instrument where id = :id").param("id", id.value).query(Long::class.java).single() > 0
}
