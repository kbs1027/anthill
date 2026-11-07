package com.anthill.marketdata.candle

import com.anthill.contract.Candle
import com.anthill.contract.ContractJson
import com.anthill.marketdata.candle.CandleRepository.Companion.utc
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock

/** 정합성 검사·복구에서 고친 내용을 남긴다. 지표 `anthill.sync.corrections{unit,kind}`. */
@Component
class CorrectionLog(private val jdbc: JdbcClient, private val meter: MeterRegistry, private val clock: Clock) {

    private val mapper = ContractJson.mapper()

    fun record(kind: String, before: Candle?, after: Candle?) {
        val c = requireNotNull(after ?: before)
        jdbc.sql(
            """
            insert into sync_correction (instrument_id, unit, start_at, kind, before, after, detected_at)
            values (:i, :u, :s, :kind, cast(:before as jsonb), cast(:after as jsonb), :at)
            """.trimIndent(),
        ).param("i", c.instrument.value).param("u", c.unit.code).param("s", utc(c.start)).param("kind", kind)
            .param("before", before?.let(mapper::writeValueAsString)).param("after", after?.let(mapper::writeValueAsString))
            .param("at", utc(clock.instant())).update()
        meter.counter("anthill.sync.corrections", "unit", c.unit.code, "kind", kind).increment()
    }
}
