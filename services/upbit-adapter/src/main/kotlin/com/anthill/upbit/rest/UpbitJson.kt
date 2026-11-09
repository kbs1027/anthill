package com.anthill.upbit.rest

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.TickerSnapshot
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 업비트 REST·웹소켓 응답을 공통 계약 타입으로 바꾼다. 숫자는 double을 거치지 않고 BigDecimal로 읽는다. */
object UpbitJson {
    const val SOURCE = "UPBIT"

    val mapper: JsonMapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    fun id(market: String) = InstrumentId.of(SOURCE, market)

    fun candle(n: JsonNode, market: String, unit: CandleUnit) = Candle(
        id(market), unit,
        LocalDateTime.parse(n["candle_date_time_utc"].asString()).toInstant(ZoneOffset.UTC),
        n["opening_price"].decimalValue(), n["high_price"].decimalValue(), n["low_price"].decimalValue(),
        n["trade_price"].decimalValue(), n["candle_acc_trade_volume"].decimalValue(), n["candle_acc_trade_price"].decimalValue(),
    )

    /** 티커의 당일 값은 업비트 당일 일봉과 수치상 같다(2026-11-05 실측). */
    fun ticker(n: JsonNode, market: String): TickerSnapshot {
        val instrument = id(market)
        val dayStart = LocalDate.parse(n["trade_date"].asString(), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant()
        return TickerSnapshot(
            instrument, n["trade_price"].decimalValue(), n["signed_change_rate"].decimalValue(), n["acc_trade_price_24h"].decimalValue(),
            Candle(
                instrument, CandleUnit.DAY, dayStart,
                n["opening_price"].decimalValue(), n["high_price"].decimalValue(), n["low_price"].decimalValue(),
                n["trade_price"].decimalValue(), n["acc_trade_volume"].decimalValue(), n["acc_trade_price"].decimalValue(),
            ),
        )
    }

    fun JsonNode.elements(): List<JsonNode> = (0 until size()).map { get(it) }
}
