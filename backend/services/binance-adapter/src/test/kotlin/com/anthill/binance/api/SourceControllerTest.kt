package com.anthill.binance.api

import com.anthill.binance.FakeRest
import com.anthill.binance.rest.TradingDay
import com.anthill.binance.stream.DayBook
import com.anthill.binance.stream.StreamControl
import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.ContractJson
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import com.anthill.contract.SecondCandle
import com.anthill.contract.StreamStatus
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class SourceControllerTest {

    private val btc = InstrumentId.parse("BINANCE:BTCUSDT")
    private val rest = FakeRest()
    private val dayBook = DayBook()
    private val stream = object : StreamControl {
        override fun resubscribe(symbols: List<String>) {}
        override fun pause(d: Duration) {}
        override val status = StreamStatus(false, null)
    }
    private val mvc = MockMvcBuilders.standaloneSetup(SourceController(rest, stream, dayBook))
        .setMessageConverters(JacksonJsonHttpMessageConverter(ContractJson.mapper()))
        .build()

    private fun candle(at: String, open: String) =
        Candle(btc, CandleUnit.M1, Instant.parse(at), BigDecimal(open), BigDecimal("2"), BigDecimal("1"), BigDecimal("2"), BigDecimal("0.50000000"), BigDecimal("1.00"))

    @Test
    fun `internal candles returns strings newest first`() {
        rest.candles = listOf(candle("2026-11-05T11:24:00Z", "1.00000000"), candle("2026-11-05T11:23:00Z", "1"))
        mvc.get("/internal/candles?instrument=BINANCE:BTCUSDT&unit=10m&to=2026-11-05T11:25:00Z&count=2&priority=USER").andExpect {
            status { isOk() }
            jsonPath("$[0].time") { value("2026-11-05T11:24:00Z") }
            jsonPath("$[0].open") { value("1") }
            jsonPath("$[0].volume") { value("0.5") }
            jsonPath("$[0].amount") { value("1") }
            jsonPath("$[1].time") { value("2026-11-05T11:23:00Z") }
        }
        assertEquals(listOf("BTCUSDT", CandleUnit.M10, Instant.parse("2026-11-05T11:25:00Z"), 2, Priority.USER), rest.lastCall)
    }

    @Test
    fun `rejects non binance instrument and bad unit or count`() {
        mvc.get("/internal/candles?instrument=UPBIT:KRW-BTC&unit=1m&count=2").andExpect { status { isBadRequest() } }
        mvc.get("/internal/candles?instrument=BINANCE:BTCUSDT&unit=2m&count=2").andExpect { status { isBadRequest() } }
        mvc.get("/internal/candles?instrument=BINANCE:BTCUSDT&unit=1m&count=201").andExpect { status { isBadRequest() } }
        mvc.get("/internal/candles?instrument=BINANCE:BTCUSDT&unit=1m&count=0").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `internal seconds returns oldest first and passes the range`() {
        val s = { at: String -> SecondCandle(btc, Instant.parse(at), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal("1.50"), BigDecimal.ONE, BigDecimal.ONE) }
        rest.seconds = listOf(s("2026-11-05T11:24:00Z"), s("2026-11-05T11:24:01Z"))
        mvc.get("/internal/seconds?instrument=BINANCE:BTCUSDT&from=2026-11-05T11:24:00Z&to=2026-11-05T11:24:02Z&priority=RECOVERY").andExpect {
            status { isOk() }
            jsonPath("$[0].start") { value("2026-11-05T11:24:00Z") }
            jsonPath("$[0].instrument") { value("BINANCE:BTCUSDT") }
            jsonPath("$[0].close") { value("1.5") }
            jsonPath("$[1].start") { value("2026-11-05T11:24:01Z") }
        }
        assertEquals(listOf("BTCUSDT", Instant.parse("2026-11-05T11:24:00Z"), Instant.parse("2026-11-05T11:24:02Z"), Priority.RECOVERY), rest.lastCall)
    }

    @Test
    fun `internal seconds rejects more than 1000 seconds and empty ranges`() {
        mvc.get("/internal/seconds?instrument=BINANCE:BTCUSDT&from=2026-11-05T11:00:00Z&to=2026-11-05T11:16:41Z").andExpect { status { isBadRequest() } }
        mvc.get("/internal/seconds?instrument=BINANCE:BTCUSDT&from=2026-11-05T11:00:00Z&to=2026-11-05T11:16:40Z").andExpect { status { isOk() } }
        mvc.get("/internal/seconds?instrument=BINANCE:BTCUSDT&from=2026-11-05T11:00:00Z&to=2026-11-05T11:00:00Z").andExpect { status { isBadRequest() } }
        mvc.get("/internal/seconds?instrument=UPBIT:KRW-BTC&from=2026-11-05T11:00:00Z&to=2026-11-05T11:00:01Z").andExpect { status { isBadRequest() } }
        mvc.get("/internal/seconds?instrument=BINANCE:BTCUSDT&from=nope&to=2026-11-05T11:00:01Z").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `instruments lists ids as strings with base asset names`() {
        rest.markets = listOf(InstrumentInfo(btc, "BTC", "BTC"))
        mvc.get("/internal/instruments").andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value("BINANCE:BTCUSDT") }
            jsonPath("$[0].koreanName") { value("BTC") }
            jsonPath("$[0].englishName") { value("BTC") }
        }
    }

    @Test
    fun `tickers come from REST until the day book is anchored`() {
        val day = Instant.parse("2026-11-10T00:00:00Z")
        rest.markets = listOf(InstrumentInfo(btc, "BTC", "BTC"))
        rest.tradingDays = listOf(TradingDay("BTCUSDT", Candle(btc, CandleUnit.DAY, day, BigDecimal("100"), BigDecimal("120"), BigDecimal("90"), BigDecimal("110"), BigDecimal("5"), BigDecimal("500")), 1000))
        rest.amounts = mapOf("BTCUSDT" to BigDecimal("777.5"))
        mvc.get("/internal/tickers").andExpect {
            status { isOk() }
            jsonPath("$[0].instrument") { value("BINANCE:BTCUSDT") }
            jsonPath("$[0].tradePrice") { value("110") }
            jsonPath("$[0].signedChangeRate") { value("0.1") }
            jsonPath("$[0].accTradePrice24h") { value("777.5") }
            jsonPath("$[0].day.unit") { value("day") }
            jsonPath("$[0].day.time") { value("2026-11-10T00:00:00Z") }
        }
        dayBook.anchor(listOf(rest.tradingDays.single().copy(day = rest.tradingDays.single().day.copy(close = BigDecimal("111")))))
        rest.markets = emptyList()
        mvc.get("/internal/tickers").andExpect {
            status { isOk() }
            jsonPath("$[0].tradePrice") { value("111") }
        }
    }
}
