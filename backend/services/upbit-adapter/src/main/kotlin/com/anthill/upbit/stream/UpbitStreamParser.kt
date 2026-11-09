package com.anthill.upbit.stream

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.TickerSnapshot
import com.anthill.contract.TradeEvent
import com.anthill.upbit.rest.UpbitJson
import java.time.Instant

sealed interface UpbitMessage
data class UpbitCandleMsg(val candle: Candle, val lastTradeAt: Instant, val snapshot: Boolean) : UpbitMessage
data class UpbitTradeMsg(val trade: TradeEvent) : UpbitMessage
data class UpbitTickerMsg(val ticker: TickerSnapshot) : UpbitMessage

object UpbitStreamParser {

    /** 웹소켓 하나로 받는 타입: 분봉 8종, 체결, 티커. */
    val TYPES = listOf(
        "candle.1m", "candle.3m", "candle.5m", "candle.10m", "candle.15m", "candle.30m", "candle.60m", "candle.240m", "trade", "ticker",
    )

    fun subscribeMessage(markets: List<String>, ticket: String): String = UpbitJson.mapper.writeValueAsString(
        listOf(mapOf("ticket" to ticket)) + TYPES.map { mapOf("type" to it, "codes" to markets) } + listOf(mapOf("format" to "DEFAULT")),
    )

    fun parse(text: String): UpbitMessage? {
        val n = UpbitJson.mapper.readTree(text)
        val type = n["type"]?.asString() ?: return null
        val market = n["code"]?.asString() ?: return null
        return when {
            type.startsWith("candle.") -> {
                val unit = CandleUnit.fromCode(type.removePrefix("candle.")) ?: return null
                UpbitCandleMsg(
                    UpbitJson.candle(n, market, unit),
                    Instant.ofEpochMilli(n["timestamp"].asLong()),
                    n["stream_type"]?.asString() == "SNAPSHOT",
                )
            }
            // 재연결·재구독 때 오는 체결 스냅숏은 같이 오는 분봉 스냅숏에 이미 들어 있다. 처음 보는 순번이라 두 번 더해진다(리뷰 I2).
            type == "trade" && n["stream_type"]?.asString() == "SNAPSHOT" -> null
            type == "trade" -> UpbitTradeMsg(
                TradeEvent(
                    UpbitJson.id(market), n["trade_price"].decimalValue(), n["trade_volume"].decimalValue(),
                    Instant.ofEpochMilli(n["trade_timestamp"].asLong()), n["sequential_id"].asLong(),
                ),
            )
            type == "ticker" -> UpbitTickerMsg(UpbitJson.ticker(n, market))
            else -> null
        }
    }
}
