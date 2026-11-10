package com.anthill.binance.rest

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.SecondCandle
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant

/** 바이낸스 당일 시세(`ticker/tradingDay`) 한 종목. [lastId]는 그날 마지막 체결 번호. */
data class TradingDay(val symbol: String, val day: Candle, val lastId: Long)

/**
 * 바이낸스 REST 응답을 공통 계약 타입으로 바꾼다.
 * 바이낸스는 숫자를 `"68000.01000000"` 같은 문자열로 주므로 double을 거치지 않고 바로 BigDecimal로 읽는다.
 */
object BinanceJson {
    const val SOURCE = "BINANCE"

    val mapper: JsonMapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    fun id(symbol: String) = InstrumentId.of(SOURCE, symbol)

    /** 단위별 kline interval. 10분은 바이낸스에 없어서 null(1분봉을 합쳐 만든다). */
    fun interval(unit: CandleUnit): String? = when (unit) {
        CandleUnit.M1 -> "1m"
        CandleUnit.M3 -> "3m"
        CandleUnit.M5 -> "5m"
        CandleUnit.M10 -> null
        CandleUnit.M15 -> "15m"
        CandleUnit.M30 -> "30m"
        CandleUnit.M60 -> "1h"
        CandleUnit.M240 -> "4h"
        CandleUnit.DAY -> "1d"
        CandleUnit.WEEK -> "1w"
        CandleUnit.MONTH -> "1M"
    }

    /** kline 한 줄: [0] 시작 ms, [1..4] 시·고·저·종, [5] 거래량, [7] 거래대금. */
    fun candle(row: JsonNode, symbol: String, unit: CandleUnit) = Candle(
        id(symbol), unit, Instant.ofEpochMilli(row[0].asLong()),
        dec(row[1]), dec(row[2]), dec(row[3]), dec(row[4]), dec(row[5]), dec(row[7]),
    )

    fun second(row: JsonNode, symbol: String) = SecondCandle(
        id(symbol), Instant.ofEpochMilli(row[0].asLong()),
        dec(row[1]), dec(row[2]), dec(row[3]), dec(row[4]), dec(row[5]), dec(row[7]),
    )

    /** `ticker/tradingDay` 항목 → UTC 당일 일봉. */
    fun tradingDay(n: JsonNode): TradingDay {
        val symbol = n["symbol"].asString()
        val day = Candle(
            id(symbol), CandleUnit.DAY, Instant.ofEpochMilli(n["openTime"].asLong()),
            dec(n["openPrice"]), dec(n["highPrice"]), dec(n["lowPrice"]), dec(n["lastPrice"]),
            dec(n["volume"]), dec(n["quoteVolume"]),
        )
        return TradingDay(symbol, day, n["lastId"].asLong())
    }

    fun dec(n: JsonNode): BigDecimal = if (n.isString) BigDecimal(n.asString()) else n.decimalValue()

    fun JsonNode.elements(): List<JsonNode> = (0 until size()).map { get(it) }
}
