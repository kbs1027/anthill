package com.anthill.binance.stream

import com.anthill.binance.rest.BinanceJson
import com.anthill.binance.rest.FixtureServer
import com.anthill.contract.InstrumentId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BinanceStreamParserTest {

    @Test
    fun `kline 1s becomes an exact second candle with trade ids`() {
        val m = assertNotNull(BinanceStreamParser.parse(FixtureServer.fixture("stream-kline-1s.json")))
        val s = m.second
        assertEquals(InstrumentId.parse("BINANCE:BTCUSDT"), s.instrument)
        assertEquals(Instant.ofEpochMilli(1794323220000), s.start)
        assertEquals(BigDecimal("74543.98000000"), s.open)
        assertEquals(BigDecimal("74543.98000000"), s.high)
        assertEquals(BigDecimal("74540.01000000"), s.low)
        assertEquals(BigDecimal("74540.01000000"), s.close)
        assertEquals(BigDecimal("0.08711000"), s.volume)
        assertEquals(BigDecimal("6493.45849930"), s.amount, "거래대금은 q")
        assertEquals(6835876731, m.firstTradeId)
        assertEquals(6835876828, m.lastTradeId)
    }

    @Test
    fun `second without trades keeps previous close and minus one ids`() {
        val m = assertNotNull(BinanceStreamParser.parse(FixtureServer.fixture("stream-kline-1s-quiet.json")))
        assertEquals(0, m.second.volume.signum())
        assertEquals(0, m.second.amount.signum())
        assertEquals(BigDecimal("0.00166600"), m.second.open)
        assertEquals(m.second.open, m.second.close)
        assertEquals(-1, m.firstTradeId)
        assertEquals(-1, m.lastTradeId)
    }

    @Test
    fun `subscribe responses, open klines and other intervals are ignored`() {
        val raw = FixtureServer.fixture("stream-kline-1s.json")
        assertNull(BinanceStreamParser.parse("""{"result":null,"id":1}"""))
        assertNull(BinanceStreamParser.parse("""{"error":{"code":2,"msg":"Invalid request"},"id":2}"""))
        assertNull(BinanceStreamParser.parse(raw.replace("\"x\":true", "\"x\":false")))
        assertNull(BinanceStreamParser.parse(raw.replace("\"i\":\"1s\"", "\"i\":\"1m\"")))
        assertNull(BinanceStreamParser.parse("""{"e":"trade","s":"BTCUSDT"}"""))
    }

    @Test
    fun `subscribe messages carry 200 streams each with rising ids`() {
        val symbols = (1..450).map { "S${it}USDT" }
        val messages = BinanceStreamParser.subscribeMessages(symbols, 7)
        assertEquals(3, messages.size)
        val first = BinanceJson.mapper.readTree(messages[0])
        assertEquals("SUBSCRIBE", first["method"].asString())
        assertEquals(200, first["params"].size())
        assertEquals("s1usdt@kline_1s", first["params"][0].asString())
        assertEquals(7, first["id"].asInt())
        assertEquals(9, BinanceJson.mapper.readTree(messages[2])["id"].asInt())
        assertEquals(50, BinanceJson.mapper.readTree(messages[2])["params"].size())
        assertEquals("UNSUBSCRIBE", BinanceJson.mapper.readTree(BinanceStreamParser.unsubscribeMessages(listOf("BTCUSDT"), 1)[0])["method"].asString())
    }
}
