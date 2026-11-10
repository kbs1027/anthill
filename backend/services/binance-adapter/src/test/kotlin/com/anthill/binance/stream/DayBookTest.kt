package com.anthill.binance.stream

import com.anthill.binance.rest.TradingDay
import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.Decimals
import com.anthill.contract.InstrumentId
import com.anthill.contract.SecondCandle
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayBookTest {

    private val btc = InstrumentId.parse("BINANCE:BTCUSDT")
    private val day = Instant.parse("2026-11-10T00:00:00Z")

    private fun d(v: String) = BigDecimal(v)

    private fun anchorDay(open: String = "100", close: String = "110", volume: String = "5", amount: String = "500", lastId: Long = 1000) =
        TradingDay("BTCUSDT", Candle(btc, CandleUnit.DAY, day, d(open), d("120"), d("90"), d(close), d(volume), d(amount)), lastId)

    /** [at] 초의 1초 캔들. 체결 번호 first..last, 거래 없으면 -1. */
    private fun sec(at: String, price: String, volume: String = "1", first: Long, last: Long, high: String = price, low: String = price) =
        BinanceKlineMsg(SecondCandle(btc, Instant.parse(at), d(price), d(high), d(low), d(price), d(volume), d(price) * d(volume)), first, last)

    private fun quiet(at: String, price: String) =
        BinanceKlineMsg(SecondCandle(btc, Instant.parse(at), d(price), d(price), d(price), d(price), d("0"), d("0")), -1, -1)

    @Test
    fun `seconds at or before the anchor lastId and straddling seconds are skipped`() {
        val book = DayBook()
        book.anchor(listOf(anchorDay(lastId = 1000)))
        assertNull(book.onSecond(sec("2026-11-10T10:00:00Z", "130", first = 990, last = 1000)), "이미 REST 값에 들어 있음")
        assertNull(book.onSecond(sec("2026-11-10T10:00:01Z", "130", first = 999, last = 1003)), "앵커에 걸친 초")
        val t = assertNotNull(book.onSecond(sec("2026-11-10T10:00:02Z", "130", volume = "2", first = 1004, last = 1006)))
        assertTrue(Decimals.same(d("130"), t.day.high))
        assertTrue(Decimals.same(d("130"), t.day.close))
        assertTrue(Decimals.same(d("100"), t.day.open))
        assertTrue(Decimals.same(d("7"), t.day.volume))
        assertTrue(Decimals.same(d("760"), t.day.amount))
    }

    @Test
    fun `re-anchor replays recent seconds that came after the REST value`() {
        val book = DayBook()
        book.anchor(listOf(anchorDay(lastId = 1000)))
        book.onSecond(sec("2026-11-10T10:00:00Z", "111", first = 1001, last = 1002))
        book.onSecond(sec("2026-11-10T10:00:01Z", "112", first = 1003, last = 1004))
        book.onSecond(sec("2026-11-10T10:00:02Z", "113", first = 1005, last = 1006))
        // REST 값은 1004번 체결까지 담고 있다 → 1005~1006 초만 다시 더한다.
        book.anchor(listOf(anchorDay(close = "112", volume = "7", amount = "723", lastId = 1004)))
        val day = book.snapshots().single().day
        assertTrue(Decimals.same(d("113"), day.close))
        assertTrue(Decimals.same(d("8"), day.volume))
        assertTrue(Decimals.same(d("836"), day.amount))
    }

    @Test
    fun `a new UTC day opens a new daily candle`() {
        val book = DayBook()
        book.anchor(listOf(anchorDay()))
        val t = assertNotNull(book.onSecond(quiet("2026-11-11T00:00:00Z", "110")))
        assertEquals(Instant.parse("2026-11-11T00:00:00Z"), t.day.start)
        assertEquals(0, t.day.volume.signum())
        assertTrue(Decimals.same(d("110"), t.day.open))
        // 새 날의 첫 거래가 시가가 된다.
        val t2 = assertNotNull(book.onSecond(sec("2026-11-11T00:00:01Z", "105", first = 2000, last = 2000)))
        assertTrue(Decimals.same(d("105"), t2.day.open))
        assertTrue(Decimals.same(d("1"), t2.day.volume))
        // 늦게 온 전날 초는 버린다.
        assertNull(book.onSecond(sec("2026-11-10T23:59:59Z", "999", first = 2001, last = 2001)))
    }

    @Test
    fun `change rate is against the UTC day open with 8 decimals`() {
        val book = DayBook()
        book.setAmounts(mapOf("BTCUSDT" to d("123456.78")))
        book.anchor(listOf(anchorDay(open = "3", close = "3")))
        val t = assertNotNull(book.onSecond(sec("2026-11-10T10:00:00Z", "4", first = 1001, last = 1001)))
        assertEquals(d("0.33333333"), t.signedChangeRate)
        assertTrue(Decimals.same(d("4"), t.tradePrice))
        assertEquals(d("123456.78"), t.accTradePrice24h)
        assertEquals(btc, t.instrument)
    }

    @Test
    fun `quiet seconds do not produce a ticker`() {
        val book = DayBook()
        book.anchor(listOf(anchorDay()))
        assertNull(book.onSecond(quiet("2026-11-10T10:00:00Z", "110")))
    }

    @Test
    fun `nothing before the first anchor`() {
        val book = DayBook()
        assertTrue(!book.ready)
        assertNull(book.onSecond(sec("2026-11-10T10:00:00Z", "130", first = 1, last = 1)))
        assertTrue(book.snapshots().isEmpty())
    }
}
