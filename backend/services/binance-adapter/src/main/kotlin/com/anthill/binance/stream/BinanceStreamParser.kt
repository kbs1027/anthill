package com.anthill.binance.stream

import com.anthill.binance.rest.BinanceJson
import com.anthill.binance.rest.BinanceJson.dec
import com.anthill.contract.SecondCandle
import java.time.Instant
import java.util.logging.Logger

/**
 * 마감된 1초 캔들 하나와 그 초의 첫·마지막 체결 번호.
 * 거래가 없던 초는 체결 번호가 둘 다 -1이다.
 */
data class BinanceKlineMsg(val second: SecondCandle, val firstTradeId: Long, val lastTradeId: Long)

object BinanceStreamParser {

    /** 구독 메시지 하나에 넣는 스트림 수. */
    const val BATCH = 200

    private val log = Logger.getLogger(BinanceStreamParser::class.java.name)

    fun streamName(symbol: String) = "${symbol.lowercase()}@kline_1s"

    /** `{"method":"SUBSCRIBE","params":[...],"id":n}`를 [BATCH]개씩. id는 [startId]부터 1씩 늘린다. */
    fun subscribeMessages(symbols: List<String>, startId: Int): List<String> = messages("SUBSCRIBE", symbols, startId)

    fun unsubscribeMessages(symbols: List<String>, startId: Int): List<String> = messages("UNSUBSCRIBE", symbols, startId)

    private fun messages(method: String, symbols: List<String>, startId: Int): List<String> =
        symbols.chunked(BATCH).mapIndexed { i, chunk ->
            BinanceJson.mapper.writeValueAsString(mapOf("method" to method, "params" to chunk.map(::streamName), "id" to startId + i))
        }

    /** 마감된(`x=true`) 1초 kline만 돌려준다. 구독 응답·다른 메시지는 null. */
    fun parse(text: String): BinanceKlineMsg? {
        val n = BinanceJson.mapper.readTree(text)
        if (n.has("result") || n.has("error")) {
            val error = n["error"]
            if (error != null && !error.isNull) log.warning("바이낸스 구독 요청이 실패했습니다: $text")
            return null
        }
        if (n["e"]?.asString() != "kline") return null
        val k = n["k"] ?: return null
        if (k["i"]?.asString() != "1s" || k["x"]?.asBoolean() != true) return null
        val symbol = k["s"].asString()
        val second = SecondCandle(
            BinanceJson.id(symbol), Instant.ofEpochMilli(k["t"].asLong()),
            dec(k["o"]), dec(k["h"]), dec(k["l"]), dec(k["c"]), dec(k["v"]), dec(k["q"]),
        )
        return BinanceKlineMsg(second, k["f"].asLong(), k["L"].asLong())
    }
}
