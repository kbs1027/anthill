package com.anthill.binance.rest

import com.anthill.contract.CandleUnit
import com.anthill.contract.Decimals
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class BinanceRestClientTest {

    private fun client(
        server: FixtureServer,
        maxStreams: Int = 1000,
        budget: WeightBudget = WeightBudget(6000),
        now: Instant = Instant.parse("2026-11-10T12:00:00Z"),
    ) = HttpBinanceRestClient(server.baseUrl, budget, Clock.fixed(now, ZoneOffset.UTC), maxStreams)

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(2)
        check(cond()) { "condition not met" }
    }

    private fun decodedQuery(uri: String) = URLDecoder.decode(uri.substringAfter('?'), Charsets.UTF_8)

    @Test
    fun `parses decimals exactly and returns newest first`() {
        FixtureServer(mapOf("klines" to "klines-zilusdt-1m.json")).use { server ->
            val candles = client(server).candles("ZILUSDT", CandleUnit.M1, null, 15, Priority.USER)
            assertEquals(15, candles.size)
            assertTrue(candles.zipWithNext().all { (a, b) -> a.start > b.start }, "최신→과거")
            val oldest = candles.last()
            assertEquals(Instant.ofEpochMilli(1794321900000), oldest.start)
            assertEquals(InstrumentId.parse("BINANCE:ZILUSDT"), oldest.instrument)
            assertEquals(BigDecimal("0.00166600"), oldest.open)
            assertEquals(BigDecimal("697777.70000000"), oldest.volume)
            assertEquals(BigDecimal("1163.66865010"), oldest.amount, "거래대금은 [7]번 칸, double을 거치지 않는다")
        }
    }

    @Test
    fun `to is sent as endTime minus one millisecond`() {
        FixtureServer(mapOf("klines" to "klines-zilusdt-1m.json")).use { server ->
            client(server).candles("ZILUSDT", CandleUnit.M1, Instant.parse("2026-11-10T11:23:00Z"), 200, Priority.USER)
            val q = FixtureServer.query(java.net.URI(server.requests.single()))
            assertEquals((Instant.parse("2026-11-10T11:23:00Z").toEpochMilli() - 1).toString(), q["endTime"])
            assertEquals("200", q["limit"])
            assertEquals("ZILUSDT", q["symbol"])
            assertEquals("1m", q["interval"])
        }
    }

    @Test
    fun `no to means no endTime`() {
        FixtureServer(mapOf("klines" to "klines-zilusdt-1m.json")).use { server ->
            client(server).candles("ZILUSDT", CandleUnit.M1, null, 3, Priority.USER)
            assertTrue("endTime" !in FixtureServer.query(java.net.URI(server.requests.single())))
        }
    }

    @Test
    fun `units map to binance intervals`() {
        val expected = mapOf(
            CandleUnit.M1 to "1m", CandleUnit.M3 to "3m", CandleUnit.M5 to "5m", CandleUnit.M15 to "15m", CandleUnit.M30 to "30m",
            CandleUnit.M60 to "1h", CandleUnit.M240 to "4h", CandleUnit.DAY to "1d", CandleUnit.WEEK to "1w", CandleUnit.MONTH to "1M",
        )
        FixtureServer(mapOf("klines" to "klines-zilusdt-15m.json")).use { server ->
            val c = client(server)
            expected.forEach { (unit, interval) ->
                val candles = c.candles("ZILUSDT", unit, null, 1, Priority.USER)
                assertEquals(unit, candles.single().unit)
                assertEquals(interval, FixtureServer.query(java.net.URI(server.requests.last()))["interval"], unit.code)
            }
        }
    }

    @Test
    fun `ten minute candles merge one minute pages and skip buckets before listing`() {
        val to = Instant.parse("2026-11-10T12:00:00Z")
        val first = to.minusSeconds(200 * 600L) // 첫 칸 시작
        val listing = first.plusSeconds(25 * 60L) // 상장: 첫 두 칸은 비고 셋째 칸은 5분만 있다
        val server = FixtureServer(emptyMap(), dynamic = { uri ->
            val q = FixtureServer.query(uri)
            val start = q.getValue("startTime").toLong()
            val end = q.getValue("endTime").toLong()
            val limit = q.getValue("limit").toInt()
            val rows = generateSequence(start) { it + 60_000 }.takeWhile { it <= end }
                .filter { it >= listing.toEpochMilli() }.take(limit)
                .map { t ->
                    // 분마다 값이 달라야 합치기 결과를 볼 수 있다: 시가 = 분 번호, 고가 +2, 저가 -1, 종가 +1.
                    val m = (t - first.toEpochMilli()) / 60_000
                    """[$t,"$m.0","${m + 2}.0","${m - 1}.0","${m + 1}.0","1.5",${t + 59_999},"3.25",1,"0","0","0"]"""
                }
            rows.joinToString(",", "[", "]")
        })
        server.use {
            val candles = client(server).candles("ZILUSDT", CandleUnit.M10, to, 200, Priority.BACKFILL)
            assertEquals(2, server.calls.get(), "1분봉 2000개는 1000개씩 두 번")
            val q1 = FixtureServer.query(java.net.URI(server.requests[0]))
            val q2 = FixtureServer.query(java.net.URI(server.requests[1]))
            assertEquals(first.toEpochMilli().toString(), q1["startTime"])
            assertEquals("1m", q1["interval"])
            assertEquals((to.toEpochMilli() - 1).toString(), q2["endTime"])
            assertEquals(198, candles.size, "상장 전 두 칸은 뺀다")
            assertEquals(Instant.parse("2026-11-10T11:50:00Z"), candles.first().start)
            assertTrue(candles.all { it.unit == CandleUnit.M10 })
            val partial = candles.last()
            assertEquals(first.plusSeconds(1200), partial.start)
            assertTrue(Decimals.same(BigDecimal("7.5"), partial.volume), "셋째 칸은 5분만: ${partial.volume}")
            assertTrue(Decimals.same(BigDecimal("16.25"), partial.amount))
            assertTrue(Decimals.same(BigDecimal("25"), partial.open))
            assertTrue(Decimals.same(BigDecimal("31"), partial.high))
            assertTrue(Decimals.same(BigDecimal("24"), partial.low))
            assertTrue(Decimals.same(BigDecimal("30"), partial.close))
            val full = candles.first()
            assertTrue(Decimals.same(BigDecimal("15"), full.volume))
            assertTrue(Decimals.same(BigDecimal("1990"), full.open))
            assertTrue(Decimals.same(BigDecimal("2000"), full.close))
        }
    }

    @Test
    fun `first ten minute bucket with only some minutes merges what exists into one candle`() {
        val bucket = Instant.parse("2026-11-10T12:00:00Z")
        // 상장 첫 칸: 12:03~12:09 일곱 개만 있다. 12:03·12:06은 거래 없음(o=h=l=c=직전 종가, 거래량 0).
        val minutes = listOf(
            listOf("1.00", "1.00", "1.00", "1.00", "0", "0"),
            listOf("1.10", "1.30", "1.05", "1.20", "10", "11.5"),
            listOf("1.20", "1.25", "0.90", "1.00", "5", "5.25"),
            listOf("1.00", "1.00", "1.00", "1.00", "0", "0"),
            listOf("1.00", "1.40", "0.95", "1.35", "2.5", "3.125"),
            listOf("1.35", "1.36", "1.30", "1.31", "1", "1.33"),
            listOf("1.31", "1.50", "1.31", "1.45", "0.5", "0.7"),
        )
        val body = minutes.mapIndexed { i, m ->
            val t = bucket.plusSeconds((3L + i) * 60).toEpochMilli()
            """[$t,"${m[0]}","${m[1]}","${m[2]}","${m[3]}","${m[4]}",${t + 59_999},"${m[5]}",1,"0","0","0"]"""
        }.joinToString(",", "[", "]")
        FixtureServer(emptyMap(), dynamic = { body }).use { server ->
            val to = bucket.plusSeconds(600)
            val candles = client(server).candles("NEWUSDT", CandleUnit.M10, to, 1, Priority.USER)
            val q = FixtureServer.query(java.net.URI(server.requests.single()))
            assertEquals(bucket.toEpochMilli().toString(), q["startTime"])
            assertEquals((to.toEpochMilli() - 1).toString(), q["endTime"])
            val c = candles.single()
            assertEquals(InstrumentId.parse("BINANCE:NEWUSDT"), c.instrument)
            assertEquals(CandleUnit.M10, c.unit)
            assertEquals(bucket, c.start, "칸 시작은 첫 1분봉(12:03)이 아니라 12:00")
            assertTrue(Decimals.same(BigDecimal("1.10"), c.open), "시가는 첫 거래 있는 1분봉(12:04)의 시가: ${c.open}")
            assertTrue(Decimals.same(BigDecimal("1.50"), c.high), "${c.high}")
            assertTrue(Decimals.same(BigDecimal("0.90"), c.low), "${c.low}")
            assertTrue(Decimals.same(BigDecimal("1.45"), c.close), "${c.close}")
            assertTrue(Decimals.same(BigDecimal("19"), c.volume), "${c.volume}")
            assertTrue(Decimals.same(BigDecimal("21.905"), c.amount), "${c.amount}")
        }
    }

    @Test
    fun `seconds asks for one second klines with exclusive end`() {
        FixtureServer(mapOf("klines" to "klines-zilusdt-1s.json")).use { server ->
            val from = Instant.ofEpochMilli(1794321900000)
            val seconds = client(server).seconds("ZILUSDT", from, from.plusSeconds(60), Priority.RECOVERY)
            assertEquals(60, seconds.size)
            assertEquals(from, seconds.first().start, "과거→최신")
            val q = FixtureServer.query(java.net.URI(server.requests.single()))
            assertEquals("1s", q["interval"])
            assertEquals(from.toEpochMilli().toString(), q["startTime"])
            assertEquals((from.plusSeconds(60).toEpochMilli() - 1).toString(), q["endTime"])
            assertFailsWith<IllegalArgumentException> { client(server).seconds("ZILUSDT", from, from.plusSeconds(1001), Priority.RECOVERY) }
        }
    }

    @Test
    fun `markets keeps only USDT symbols with base asset as names, capped`() {
        FixtureServer(mapOf("exchangeInfo" to "exchange-info.json")).use { server ->
            val all = client(server).markets()
            assertEquals(listOf("BINANCE:BTCUSDT", "BINANCE:ETHUSDT", "BINANCE:ZILUSDT"), all.map { it.id.value })
            assertEquals(InstrumentInfo(InstrumentId.parse("BINANCE:BTCUSDT"), "BTC", "BTC"), all.first())
            val query = decodedQuery(server.requests.first())
            assertTrue(query.contains("permissions=SPOT") && query.contains("symbolStatus=TRADING"), query)
            assertEquals(listOf("BINANCE:BTCUSDT", "BINANCE:ETHUSDT"), client(server, maxStreams = 2).markets().map { it.id.value })
        }
    }

    @Test
    fun `trading days are requested 100 symbols at a time`() {
        FixtureServer(mapOf("ticker/tradingDay" to "ticker-trading-day.json")).use { server ->
            val symbols = (1..150).map { "S${it}USDT" }
            val days = client(server).tradingDays(symbols)
            assertEquals(2, server.calls.get())
            val first = decodedQuery(server.requests[0]).removePrefix("symbols=")
            assertEquals(100, first.count { it == ',' } + 1)
            assertTrue(first.startsWith("[\"S1USDT\""), first)
            assertEquals(50, decodedQuery(server.requests[1]).count { it == ',' } + 1)
            val btc = days.first { it.symbol == "BTCUSDT" }
            assertEquals(Instant.ofEpochMilli(1794268800000), btc.day.start)
            assertEquals(CandleUnit.DAY, btc.day.unit)
            assertEquals(BigDecimal("75368.41000000"), btc.day.open)
            assertEquals(BigDecimal("74560.06000000"), btc.day.close)
            assertEquals(BigDecimal("658198628.76551510"), btc.day.amount)
            assertEquals(6835875319, btc.lastId)
        }
    }

    @Test
    fun `amounts are 24 hour quote volume`() {
        FixtureServer(mapOf("ticker/24hr" to "ticker-24hr-mini.json")).use { server ->
            val amounts = client(server).amounts24h()
            assertTrue("type=MINI" in server.requests.single())
            val raw = FixtureServer.fixture("ticker-24hr-mini.json")
            val btc = Regex("\"symbol\": ?\"BTCUSDT\"[^}]*\"quoteVolume\": ?\"([0-9.]+)\"").find(raw)!!.groupValues[1]
            assertEquals(BigDecimal(btc), amounts["BTCUSDT"])
        }
    }

    @Test
    fun `retries after 429 once Retry-After passes`() {
        FixtureServer(
            mapOf("klines" to "klines-zilusdt-1m.json"),
            status = { if (it == 1) 429 else 200 },
            headers = { if (it == 1) mapOf("Retry-After" to "1") else emptyMap() },
        ).use { server ->
            val started = System.nanoTime()
            val candles = client(server).candles("ZILUSDT", CandleUnit.M1, null, 15, Priority.USER)
            val waitedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(15, candles.size)
            assertEquals(2, server.calls.get())
            assertTrue(waitedMs >= 900, "Retry-After 1초를 기다려야 한다: ${waitedMs}ms")
        }
    }

    @Test
    fun `451 fails with a region block message`() {
        FixtureServer(emptyMap(), status = { 451 }).use { server ->
            val e = assertFailsWith<BinanceApiException> { client(server).markets() }
            assertEquals(451, e.status)
            assertTrue(e.message!!.contains("지역"), e.message)
            assertEquals(1, server.calls.get())
        }
    }

    @Test
    fun `418 pauses and fails without retry`() {
        FixtureServer(emptyMap(), status = { 418 }, headers = { mapOf("Retry-After" to "0") }).use { server ->
            assertFailsWith<BinanceApiException> { client(server).markets() }
            assertEquals(1, server.calls.get())
        }
    }

    @Test
    fun `used weight over 90 percent of 6000 holds requests until the next minute`() {
        val weightHeader = "X-MBX-USED-WEIGHT-1M"
        // 5500 > 5400: 12:00:45.250에 받았으니 다음 분(12:01:00)까지 14.75초 멈춘다.
        val time = TestTimeSource()
        val budget = WeightBudget(6000, time)
        FixtureServer(
            mapOf("klines" to "klines-zilusdt-1m.json"),
            headers = { if (it == 1) mapOf(weightHeader to "5500") else emptyMap() },
        ).use { server ->
            val c = client(server, budget = budget, now = Instant.parse("2026-11-10T12:00:45.250Z"))
            c.candles("ZILUSDT", CandleUnit.M1, null, 3, Priority.USER)
            val next = Thread.ofVirtual().start { c.candles("ZILUSDT", CandleUnit.M1, null, 3, Priority.USER) }
            waitUntil { budget.waiting == 1 }
            time += 14_749.milliseconds
            Thread.sleep(30)
            assertEquals(1, server.calls.get(), "다음 분 전에는 나가지 않는다")
            assertEquals(1, budget.waiting)
            time += 1.milliseconds
            next.join(2000)
            assertEquals(2, server.calls.get(), "분이 바뀌면 나간다")
        }
        // 5400(정확히 90%)은 넘지 않았으니 멈추지 않는다. 시계를 움직이지 않아도 다음 요청이 나간다.
        val still = TestTimeSource()
        val atLimit = WeightBudget(6000, still)
        FixtureServer(
            mapOf("klines" to "klines-zilusdt-1m.json"),
            headers = { if (it == 1) mapOf(weightHeader to "5400") else emptyMap() },
        ).use { server ->
            val c = client(server, budget = atLimit, now = Instant.parse("2026-11-10T12:00:45.250Z"))
            c.candles("ZILUSDT", CandleUnit.M1, null, 3, Priority.USER)
            val next = Thread.ofVirtual().start { c.candles("ZILUSDT", CandleUnit.M1, null, 3, Priority.USER) }
            next.join(2000)
            val sent = server.calls.get()
            next.interrupt()
            assertEquals(2, sent, "무게 5400은 멈추지 않는다")
        }
    }
}
