package com.anthill.marketdata.live

import com.anthill.contract.Candle
import com.anthill.contract.CandleState
import com.anthill.contract.CandleUnit
import com.anthill.contract.Decimals
import com.anthill.contract.SourceCandleEvent
import com.anthill.contract.TickerSnapshot
import com.anthill.contract.TradeEvent
import com.anthill.marketdata.FakeSource.Companion.BTC
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveCandleBookTest {

    private val closed = mutableListOf<Candle>()
    private val lates = mutableListOf<Candle>()
    private val book = bookAt("2026-11-08T00:00:00Z")

    private fun bookAt(now: String) =
        LiveCandleBook(Clock.fixed(Instant.parse(now), ZoneOffset.UTC), Duration.ofSeconds(10)) { c, late -> if (late) lates += c else closed += c }
    private var seq = 0L

    private fun at(s: String) = Instant.parse(s)
    private fun d(v: String) = BigDecimal(v)
    private fun trade(time: String, price: String, volume: String = "1", id: Long = ++seq) = TradeEvent(BTC, d(price), d(volume), at(time), id)
    private fun m1(start: String, o: String, h: String, l: String, c: String, v: String, a: String = "0") =
        Candle(BTC, CandleUnit.M1, at(start), d(o), d(h), d(l), d(c), d(v), d(a))
    private fun day(start: String, o: String, h: String, l: String, c: String, v: String, a: String) =
        Candle(BTC, CandleUnit.DAY, at(start), d(o), d(h), d(l), d(c), d(v), d(a))
    private fun ticker(dayCandle: Candle) = TickerSnapshot(BTC, dayCandle.close, BigDecimal.ZERO, dayCandle.amount, dayCandle)

    @Test
    fun `trade updates every minute unit`() {
        book.onTrade(trade("2026-11-08T10:07:30Z", "100"))
        book.onTrade(trade("2026-11-08T10:07:40Z", "105", "2"))
        for (u in listOf(CandleUnit.M1, CandleUnit.M5, CandleUnit.M60, CandleUnit.M240)) {
            val c = book.current(BTC, u)!!
            assertEquals(u.floor(at("2026-11-08T10:07:30Z")), c.start)
            assertTrue(Decimals.same(d("105"), c.close))
            assertTrue(Decimals.same(d("3"), c.volume))
            assertTrue(Decimals.same(d("310"), c.amount))
        }
        assertNull(book.current(BTC, CandleUnit.DAY))
    }

    @Test
    fun `older trade is ignored`() {
        book.onTrade(trade("2026-11-08T10:07:30Z", "100"))
        book.onTrade(trade("2026-11-08T10:06:59Z", "1"))
        assertTrue(Decimals.same(d("100"), book.current(BTC, CandleUnit.M1)!!.low))
    }

    @Test
    fun `next bucket trade closes previous with the trades applied after the last upbit candle`() {
        // E2E(2026-11-08 KRW-XRP 06:25): 업비트 분봉은 약 3초 간격이라 마지막 분봉 뒤 체결이 더 있다.
        // 마지막 분봉 값으로 닫으면 그 체결이 빠진 마감이 화면에 나간다(spec 7.3: 오래된 분봉은 큰 쪽을 유지).
        val upbit = m1("2026-11-08T10:00:00Z", "100", "110", "95", "108", "3", "320")
        book.onCandle(SourceCandleEvent(upbit, at("2026-11-08T10:00:50Z"), false))
        book.onTrade(trade("2026-11-08T10:00:55Z", "109"))
        book.onTrade(trade("2026-11-08T10:01:05Z", "107"))
        assertTrue(closed.single { it.unit == CandleUnit.M1 }.sameValuesAs(m1("2026-11-08T10:00:00Z", "100", "110", "95", "109", "4", "429")))
        assertEquals(at("2026-11-08T10:01:00Z"), book.current(BTC, CandleUnit.M1)!!.start)
        val updates = book.drainUpdates().filter { it.unit == CandleUnit.M1 }
        assertEquals(listOf(CandleState.CLOSED, CandleState.LIVE), updates.map { it.state })
    }

    @Test
    fun `newer authoritative wins even if smaller`() {
        book.onTrade(trade("2026-11-08T10:00:10Z", "100", "1", id = 7))
        book.onTrade(trade("2026-11-08T10:00:10Z", "100", "1", id = 8))   // 우리 쪽에서 두 번 반영된 상황
        book.onCandle(SourceCandleEvent(m1("2026-11-08T10:00:00Z", "100", "100", "100", "100", "1", "100"), at("2026-11-08T10:00:10Z"), false))
        assertTrue(Decimals.same(d("1"), book.current(BTC, CandleUnit.M1)!!.volume))
    }

    @Test
    fun `stale authoritative never shrinks display`() {
        book.onTrade(trade("2026-11-08T10:00:05Z", "101"))
        book.onCandle(SourceCandleEvent(m1("2026-11-08T10:00:00Z", "100", "100", "99", "100", "0.5", "50"), at("2026-11-08T10:00:03Z"), false))
        val c = book.current(BTC, CandleUnit.M1)!!
        assertTrue(Decimals.same(d("101"), c.high))
        assertTrue(Decimals.same(d("1"), c.volume))
        assertTrue(Decimals.same(d("101"), c.close))
        assertTrue(Decimals.same(d("99"), c.low))
    }

    @Test
    fun `repeated sequential id is ignored`() {
        book.onTrade(trade("2026-11-08T10:00:05Z", "100", "1", id = 42))
        book.onTrade(trade("2026-11-08T10:00:05Z", "100", "1", id = 42))
        assertTrue(Decimals.same(d("1"), book.current(BTC, CandleUnit.M1)!!.volume))
    }

    @Test
    fun `sweep closes illiquid candle after grace`() {
        book.onTrade(trade("2026-11-08T10:00:05Z", "100"))
        book.sweep(at("2026-11-08T10:01:09Z"))
        assertNull(closed.firstOrNull { it.unit == CandleUnit.M1 })
        book.sweep(at("2026-11-08T10:01:10Z"))
        assertEquals(at("2026-11-08T10:00:00Z"), closed.single { it.unit == CandleUnit.M1 }.start)
        assertNull(book.current(BTC, CandleUnit.M1))
    }

    @Test
    fun `late authoritative for closed bucket is handed on as late`() {
        book.onTrade(trade("2026-11-08T10:00:05Z", "100"))
        book.onTrade(trade("2026-11-08T10:01:05Z", "101"))
        closed.clear(); book.drainUpdates()
        val late = m1("2026-11-08T10:00:00Z", "100", "100", "100", "100", "2", "200")
        book.onCandle(SourceCandleEvent(late, at("2026-11-08T10:00:05Z"), false))
        assertEquals(listOf(late), lates)            // 저장소가 확인 전이면 저장하고 화면에 정정으로 보낸다
        assertEquals(emptyList(), closed)
        assertEquals(at("2026-11-08T10:01:00Z"), book.current(BTC, CandleUnit.M1)!!.start)
        assertTrue(book.drainUpdates().none { it.unit == CandleUnit.M1 && it.state == CandleState.LIVE })
    }

    @Test
    fun `final upbit value arriving after a trade closed the minute is handed on once`() {
        // 리뷰 C1: 업비트 분봉은 약 3초 간격이라, 다음 분 첫 체결이 먼저 와서 앞 분을 A1으로 닫은 뒤 최종값 A2가 온다.
        val a1 = m1("2026-11-08T10:00:00Z", "100", "110", "95", "108", "3", "320")
        book.onCandle(SourceCandleEvent(a1, at("2026-11-08T10:00:57Z"), false))
        book.onTrade(trade("2026-11-08T10:01:00.200Z", "109"))
        assertEquals(a1, closed.single { it.unit == CandleUnit.M1 })
        val a2 = m1("2026-11-08T10:00:00Z", "100", "110", "95", "107", "4", "427")
        book.onCandle(SourceCandleEvent(a2, at("2026-11-08T10:00:59.900Z"), false))
        book.onCandle(SourceCandleEvent(a2, at("2026-11-08T10:00:59.900Z"), false))   // 같은 값이 다시 와도 한 번만
        book.onCandle(SourceCandleEvent(a1, at("2026-11-08T10:00:57Z"), false))       // 마지막으로 닫은 값과 같은 옛 값은 넘기지 않는다
        assertEquals(listOf(a2), lates)
    }

    @Test
    fun `snapshot for an ended bucket never reopens it`() {
        // 리뷰 I1: 재구독하면 업비트가 모든 단위의 마지막 캔들 스냅숏을 보낸다. 몇 시간 전 캔들일 수 있다.
        val b = bookAt("2026-11-08T12:00:00Z")
        val old = m1("2026-11-08T10:00:00Z", "100", "100", "100", "100", "1", "100")
        b.onCandle(SourceCandleEvent(old, at("2026-11-08T10:00:30Z"), true))
        assertNull(b.current(BTC, CandleUnit.M1))
        assertTrue(b.drainUpdates().none { it.unit == CandleUnit.M1 })
        assertEquals(listOf(old), lates)
    }

    @Test
    fun `trade for an ended bucket is ignored`() {
        val b = bookAt("2026-11-08T12:00:00Z")
        b.onTrade(trade("2026-11-08T10:00:05Z", "100"))
        assertNull(b.current(BTC, CandleUnit.M1))
        assertNull(b.current(BTC, CandleUnit.M60))
    }

    @Test
    fun `past day ticker neither opens a day nor replaces the seeded day`() {
        // 리뷰 I1: 시작할 때 받는 REST 티커는 오늘 거래가 없으면 지난 날짜 일봉을 들고 온다.
        val b = bookAt("2026-11-08T12:00:00Z")
        val rest = day("2026-11-07T00:00:00Z", "10", "12", "9", "11", "100", "1000")
        b.seedClosedDays(BTC, listOf(rest))
        b.onTicker(ticker(day("2026-11-07T00:00:00Z", "10", "12", "9", "11", "60", "600")))
        assertNull(b.current(BTC, CandleUnit.DAY))
        b.onTicker(ticker(day("2026-11-08T00:00:00Z", "11", "13", "10", "12", "5", "60")))
        assertTrue(Decimals.same(d("105"), b.current(BTC, CandleUnit.WEEK)!!.volume))
    }

    @Test
    fun `trade already in the applied upbit value is not added again`() {
        // 리뷰 I2: 재연결 때 분봉 스냅숏(그 체결 포함) 뒤에 같은 체결의 스냅숏이 처음 보는 순번으로 온다.
        val up = m1("2026-11-08T10:05:00Z", "100", "100", "100", "100", "5", "500")
        book.onCandle(SourceCandleEvent(up, at("2026-11-08T10:05:10Z"), true))
        book.onTrade(trade("2026-11-08T10:05:10Z", "100", "1"))
        assertTrue(Decimals.same(d("5"), book.current(BTC, CandleUnit.M1)!!.volume))
        book.onTrade(trade("2026-11-08T10:05:11Z", "100", "1"))
        assertTrue(Decimals.same(d("6"), book.current(BTC, CandleUnit.M1)!!.volume))
    }

    @Test
    fun `duplicate authoritative is idempotent`() {
        val c = m1("2026-11-08T10:00:00Z", "100", "100", "100", "100", "1", "100")
        book.onCandle(SourceCandleEvent(c, at("2026-11-08T10:00:05Z"), true))
        book.drainUpdates()
        book.onCandle(SourceCandleEvent(c, at("2026-11-08T10:00:05Z"), false))
        assertTrue(book.drainUpdates().isEmpty())
    }

    @Test
    fun `ticker drives day candle`() {
        val today = day("2026-11-08T00:00:00Z", "100", "120", "90", "110", "5", "550")
        book.onTicker(ticker(today))
        assertEquals(today, book.current(BTC, CandleUnit.DAY))
        book.onTrade(trade("2026-11-08T10:00:05Z", "130"))
        assertEquals(today, book.current(BTC, CandleUnit.DAY))
    }

    @Test
    fun `week candle is closed days plus ticker day`() {
        val book = bookAt("2026-11-04T12:00:00Z")   // 티커 일봉은 오늘 것만 받는다
        book.seedClosedDays(BTC, listOf(
            day("2026-11-02T00:00:00Z", "90", "100", "80", "95", "1", "90"),
            day("2026-11-03T00:00:00Z", "95", "130", "94", "120", "2", "230"),
        ))
        book.onTicker(ticker(day("2026-11-04T00:00:00Z", "120", "125", "70", "121", "3", "360")))
        val week = book.current(BTC, CandleUnit.WEEK)!!
        assertEquals(at("2026-11-02T00:00:00Z"), week.start)
        assertTrue(Decimals.same(d("90"), week.open))
        assertTrue(Decimals.same(d("130"), week.high))
        assertTrue(Decimals.same(d("70"), week.low))
        assertTrue(Decimals.same(d("121"), week.close))
        assertTrue(Decimals.same(d("6"), week.volume))
        assertTrue(Decimals.same(d("680"), week.amount))
        val month = book.current(BTC, CandleUnit.MONTH)!!
        assertEquals(at("2026-11-01T00:00:00Z"), month.start)
        assertTrue(Decimals.same(d("6"), month.volume))
    }

    @Test
    fun `week boundary closes previous week when Monday ticker arrives`() {
        book.onTicker(ticker(day("2026-11-08T00:00:00Z", "100", "100", "100", "100", "1", "100")))
        assertEquals(at("2026-11-02T00:00:00Z"), book.current(BTC, CandleUnit.WEEK)!!.start)
        book.onTicker(ticker(day("2026-11-09T00:00:00Z", "101", "101", "101", "101", "1", "101")))
        assertEquals(at("2026-11-09T00:00:00Z"), book.current(BTC, CandleUnit.WEEK)!!.start)
        assertEquals(at("2026-11-02T00:00:00Z"), closed.single { it.unit == CandleUnit.WEEK }.start)
        assertEquals(at("2026-11-08T00:00:00Z"), closed.single { it.unit == CandleUnit.DAY }.start)
    }
}
