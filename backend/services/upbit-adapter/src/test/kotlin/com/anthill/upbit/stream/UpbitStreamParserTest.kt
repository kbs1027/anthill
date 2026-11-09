package com.anthill.upbit.stream

import com.anthill.contract.CandleUnit
import com.anthill.contract.Decimals
import com.anthill.upbit.rest.FixtureServer
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpbitStreamParserTest {

    private fun raw(name: String) = FixtureServer.fixture(name)
    private fun number(json: String, field: String) = BigDecimal(Regex("\"$field\":([0-9.]+)").find(json)!!.groupValues[1])

    @Test
    fun `subscribe message has eight candle types plus trade and ticker`() {
        val tree = JsonMapper.builder().build().readTree(UpbitStreamParser.subscribeMessage(listOf("KRW-BTC", "KRW-XRP"), "t1"))
        val types = (1 until tree.size()).map { tree.get(it) }.filter { it.has("type") }
        assertEquals(
            setOf("candle.1m", "candle.3m", "candle.5m", "candle.10m", "candle.15m", "candle.30m", "candle.60m", "candle.240m", "trade", "ticker"),
            types.map { it["type"].asString() }.toSet(),
        )
        types.forEach { t -> assertEquals(listOf("KRW-BTC", "KRW-XRP"), (0 until t["codes"].size()).map { t["codes"].get(it).asString() }) }
    }

    @Test
    fun `parses candle message exactly`() {
        val json = raw("stream-candle-1m.json")
        val msg = assertIs<UpbitCandleMsg>(UpbitStreamParser.parse(json))
        assertEquals(CandleUnit.M1, msg.candle.unit)
        assertEquals("UPBIT:KRW-BTC", msg.candle.instrument.value)
        assertTrue(Decimals.same(number(json, "candle_acc_trade_price"), msg.candle.amount))
        assertEquals(number(json, "candle_acc_trade_price").scale(), msg.candle.amount.scale())
        assertTrue(msg.snapshot)
        assertEquals(Instant.ofEpochMilli(number(json, "timestamp").toLong()), msg.lastTradeAt)
    }

    @Test
    fun `parses trade timestamp in millis`() {
        // 기록해 둔 메시지는 구독 직후의 스냅숏이라 실시간 메시지로 바꿔 읽는다(스냅숏 체결은 버린다).
        val json = raw("stream-trade.json").replace("\"SNAPSHOT\"", "\"REALTIME\"")
        val msg = assertIs<UpbitTradeMsg>(UpbitStreamParser.parse(json))
        assertEquals(Instant.ofEpochMilli(number(json, "trade_timestamp").toLong()), msg.trade.tradedAt)
        assertEquals(number(json, "sequential_id").toLong(), msg.trade.sequentialId)
        assertTrue(Decimals.same(number(json, "trade_volume"), msg.trade.volume))
    }

    @Test
    fun `parses ticker into day candle`() {
        val msg = assertIs<UpbitTickerMsg>(UpbitStreamParser.parse(raw("stream-ticker.json")))
        assertEquals(CandleUnit.DAY, msg.ticker.day.unit)
        assertTrue(Decimals.same(msg.ticker.tradePrice, msg.ticker.day.close))
    }

    @Test
    fun `unknown type returns null`() = assertNull(UpbitStreamParser.parse("""{"type":"orderbook","code":"KRW-BTC"}"""))

    @Test
    fun `trade snapshot is dropped`() {
        // 리뷰 I2: 재연결·재구독 때 오는 체결 스냅숏은 분봉 스냅숏에 이미 들어 있는 체결이다. 처음 보는 순번이라 두 번 더해진다.
        val snap = """{"type":"trade","code":"KRW-BTC","trade_timestamp":1794071474532,"trade_price":1,"trade_volume":2,"sequential_id":7,"stream_type":"SNAPSHOT"}"""
        val real = snap.replace("SNAPSHOT", "REALTIME")
        kotlin.test.assertNull(UpbitStreamParser.parse(snap))
        kotlin.test.assertNotNull(UpbitStreamParser.parse(real))
    }
}
