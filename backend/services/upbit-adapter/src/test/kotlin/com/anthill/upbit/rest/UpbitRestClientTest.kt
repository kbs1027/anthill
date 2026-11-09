package com.anthill.upbit.rest

import com.anthill.contract.CandleUnit
import com.anthill.contract.Decimals
import com.anthill.contract.InstrumentId
import com.anthill.contract.Priority
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URLDecoder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpbitRestClientTest {

    private fun client(server: FixtureServer) =
        HttpUpbitRestClient(server.baseUrl, RequestBudget(100), retryBase = java.time.Duration.ofMillis(5))

    @Test
    fun `parses decimals exactly`() {
        FixtureServer(mapOf("candles/minutes/1" to "candles-minutes-1.json")).use { server ->
            val candles = client(server).candles("KRW-BTC", CandleUnit.M1, null, 3, Priority.USER)
            val raw = FixtureServer.fixture("candles-minutes-1.json")
            val amount = Regex("\"candle_acc_trade_price\":([0-9.]+)").find(raw)!!.groupValues[1]
            val close = Regex("\"trade_price\":([0-9.]+)").find(raw)!!.groupValues[1]
            assertTrue(Decimals.same(BigDecimal(amount), candles.first().amount))
            assertEquals(BigDecimal(amount).scale(), candles.first().amount.scale(), "must not pass through double")
            assertTrue(Decimals.same(BigDecimal(close), candles.first().close))
            assertEquals(3, candles.size)
        }
    }

    @Test
    fun `candle start is candle_date_time_utc as UTC`() {
        FixtureServer(mapOf("candles/minutes/1" to "candles-minutes-1.json")).use { server ->
            val first = client(server).candles("KRW-BTC", CandleUnit.M1, null, 3, Priority.USER).first()
            val raw = FixtureServer.fixture("candles-minutes-1.json")
            val utc = Regex("\"candle_date_time_utc\":\"([^\"]+)\"").find(raw)!!.groupValues[1]
            assertEquals(Instant.parse(utc + "Z"), first.start)
            assertEquals(InstrumentId.parse("UPBIT:KRW-BTC"), first.instrument)
        }
    }

    @Test
    fun `ticker day candle starts at trade_date 00Z`() {
        FixtureServer(mapOf("ticker/all" to "ticker-all.json")).use { server ->
            val btc = client(server).tickers().single { it.instrument.symbol == "KRW-BTC" }
            val raw = FixtureServer.fixture("ticker-all.json")
            val tradeDate = Regex("\"market\": \"KRW-BTC\", \"trade_date\": \"(\\d{8})\"").find(raw)!!.groupValues[1]
            val expected = "${tradeDate.substring(0, 4)}-${tradeDate.substring(4, 6)}-${tradeDate.substring(6)}T00:00:00Z"
            assertEquals(Instant.parse(expected), btc.day.start)
            assertEquals(CandleUnit.DAY, btc.day.unit)
            assertTrue(Decimals.same(btc.tradePrice, btc.day.close))
        }
    }

    @Test
    fun `to is sent as ISO UTC`() {
        FixtureServer(mapOf("candles/minutes/1" to "candles-minutes-1.json")).use { server ->
            client(server).candles("KRW-BTC", CandleUnit.M1, Instant.parse("2026-11-05T11:23:00Z"), 200, Priority.USER)
            val query = URLDecoder.decode(server.requests.single().substringAfter('?'), Charsets.UTF_8)
            assertTrue(query.contains("to=2026-11-05T11:23:00"), query)
            assertTrue(query.contains("count=200"), query)
            assertTrue(query.contains("market=KRW-BTC"), query)
        }
    }

    @Test
    fun `day week month use their own endpoints`() {
        FixtureServer(mapOf("candles/days" to "candles-days.json")).use { server ->
            val days = client(server).candles("KRW-BTC", CandleUnit.DAY, null, 2, Priority.USER)
            assertEquals(2, days.size)
            assertTrue(server.requests.single().startsWith("/v1/candles/days?"))
        }
    }

    @Test
    fun `retries after 429 with backoff`() {
        FixtureServer(mapOf("candles/minutes/1" to "candles-minutes-1.json"), status = { if (it == 1) 429 else 200 }).use { server ->
            val candles = client(server).candles("KRW-BTC", CandleUnit.M1, null, 3, Priority.USER)
            assertEquals(3, candles.size)
            assertEquals(2, server.calls.get())
        }
    }

    @Test
    fun `markets keeps only KRW`() {
        FixtureServer(mapOf("market/all" to "market-all.json")).use { server ->
            val ids = client(server).markets().map { it.id.value }.sorted()
            assertEquals(listOf("UPBIT:KRW-BTC", "UPBIT:KRW-XRP"), ids)
        }
    }
}
