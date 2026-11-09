package com.anthill.contract

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.Instant

data class Candle(
    val instrument: InstrumentId,
    val unit: CandleUnit,
    @JsonProperty("time") val start: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val amount: BigDecimal,
) {
    fun sameValuesAs(o: Candle): Boolean =
        instrument == o.instrument && unit == o.unit && start == o.start &&
            Decimals.same(open, o.open) && Decimals.same(high, o.high) && Decimals.same(low, o.low) &&
            Decimals.same(close, o.close) && Decimals.same(volume, o.volume) && Decimals.same(amount, o.amount)
}
