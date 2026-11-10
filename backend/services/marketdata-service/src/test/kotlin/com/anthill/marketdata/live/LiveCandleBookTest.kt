package com.anthill.marketdata.live

import com.anthill.contract.Candle
import com.anthill.contract.CandleState
import com.anthill.contract.CandleUnit
import com.anthill.contract.Decimals
import com.anthill.contract.InstrumentId
import com.anthill.contract.SecondCandle
import com.anthill.contract.SecondCandleEvent
import com.anthill.contract.SourceCandleEvent
import com.anthill.contract.TickerSnapshot
import com.anthill.contract.TradeEvent
import com.anthill.marketdata.FakeSource.Companion.BN
import com.anthill.marketdata.FakeSource.Companion.BTC
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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

    // ---- 바이낸스 1초 캔들 ----

    private val gaps = mutableListOf<Pair<InstrumentId, Instant>>()

    private fun secondBookAt(now: String) =
        LiveCandleBook(Clock.fixed(Instant.parse(now), ZoneOffset.UTC), Duration.ofSeconds(10), { i, cut -> gaps += i to cut }) { c, late ->
            if (late) lates += c else closed += c
        }

    private fun sec(time: String, o: String, h: String, l: String, c: String, v: String, a: String) =
        SecondCandleEvent(SecondCandle(BN, at(time), d(o), d(h), d(l), d(c), d(v), d(a)))

    /** 거래 없는 초: 시·고·저·종 = 직전 종가, 거래량 0. */
    private fun quiet(time: String, price: String) = sec(time, price, price, price, price, "0", "0")

    private fun assertCandle(c: Candle?, o: String, h: String, l: String, cl: String, v: String, a: String) {
        c!!
        assertTrue(
            Decimals.same(d(o), c.open) && Decimals.same(d(h), c.high) && Decimals.same(d(l), c.low) && Decimals.same(d(cl), c.close) &&
                Decimals.same(d(v), c.volume) && Decimals.same(d(a), c.amount),
            "expected o=$o h=$h l=$l c=$cl v=$v a=$a but was $c",
        )
    }

    @Test
    fun `second candle updates every minute unit`() {
        val b = secondBookAt("2026-11-08T10:07:00Z")
        b.onSecondCandle(sec("2026-11-08T10:07:30Z", "100", "101", "99", "100", "1", "100"))
        b.onSecondCandle(sec("2026-11-08T10:07:31Z", "100", "105", "100", "104", "2", "208"))
        for (u in LiveCandleBook.MINUTE_UNITS) {
            val c = b.current(BN, u)
            assertEquals(u.floor(at("2026-11-08T10:07:30Z")), c!!.start)
            assertCandle(c, "100", "105", "99", "104", "3", "308")
        }
        assertNull(b.current(BN, CandleUnit.DAY))
    }

    @Test
    fun `first traded second sets the open of a bucket opened by a quiet second and quiet seconds are not pushed`() {
        val b = secondBookAt("2026-11-08T10:00:00Z")
        b.onSecondCandle(quiet("2026-11-08T10:00:00Z", "100"))
        assertCandle(b.current(BN, CandleUnit.M1), "100", "100", "100", "100", "0", "0")   // 바이낸스의 "거래 없는 분"과 같은 값
        b.drainUpdates()
        b.onSecondCandle(quiet("2026-11-08T10:00:01Z", "100"))
        assertTrue(b.drainUpdates().isEmpty())
        b.onSecondCandle(sec("2026-11-08T10:00:02Z", "101", "103", "101", "102", "1", "102"))
        assertCandle(b.current(BN, CandleUnit.M1), "101", "103", "101", "102", "1", "102")
        b.onSecondCandle(quiet("2026-11-08T10:00:03Z", "102"))
        b.onSecondCandle(sec("2026-11-08T10:00:04Z", "102", "102", "98", "99", "2", "199"))
        assertCandle(b.current(BN, CandleUnit.M1), "101", "103", "98", "99", "3", "301")
    }

    @Test
    fun `same or earlier second is ignored`() {
        val b = secondBookAt("2026-11-08T10:00:00Z")
        b.onSecondCandle(sec("2026-11-08T10:00:05Z", "100", "100", "100", "100", "1", "100"))
        b.onSecondCandle(sec("2026-11-08T10:00:05Z", "100", "100", "100", "100", "1", "100"))
        b.onSecondCandle(sec("2026-11-08T10:00:04Z", "90", "90", "90", "90", "1", "90"))
        assertCandle(b.current(BN, CandleUnit.M1), "100", "100", "100", "100", "1", "100")
    }

    @Test
    fun `second of the next bucket closes the previous one`() {
        val b = secondBookAt("2026-11-08T10:00:00Z")
        b.onSecondCandle(sec("2026-11-08T10:00:59Z", "100", "100", "100", "100", "1", "100"))
        b.onSecondCandle(sec("2026-11-08T10:01:00Z", "101", "101", "101", "101", "1", "101"))
        assertEquals(at("2026-11-08T10:00:00Z"), closed.single { it.unit == CandleUnit.M1 }.start)
        assertEquals(at("2026-11-08T10:01:00Z"), b.current(BN, CandleUnit.M1)!!.start)
        assertTrue(closed.none { it.unit == CandleUnit.M5 })
        val m1 = b.drainUpdates().filter { it.unit == CandleUnit.M1 }
        assertEquals(listOf(CandleState.CLOSED, CandleState.LIVE), m1.map { it.state })
    }

    @Test
    fun `second for an ended bucket is ignored`() {
        val b = secondBookAt("2026-11-08T12:00:00Z")
        b.onSecondCandle(sec("2026-11-08T10:00:05Z", "100", "100", "100", "100", "1", "100"))
        assertNull(b.current(BN, CandleUnit.M1))
        assertNull(b.current(BN, CandleUnit.M60))
    }

    @Test
    fun `gap is reported once for the first second and for a skipped second`() {
        val b = secondBookAt("2026-11-08T10:00:00Z")
        b.onSecondCandle(quiet("2026-11-08T10:00:00Z", "100"))
        b.onSecondCandle(quiet("2026-11-08T10:00:01Z", "100"))
        b.onSecondCandle(quiet("2026-11-08T10:00:02Z", "100"))
        b.onSecondCandle(quiet("2026-11-08T10:00:05Z", "100"))
        b.onSecondCandle(quiet("2026-11-08T10:00:05Z", "100"))
        b.onSecondCandle(quiet("2026-11-08T10:00:06Z", "100"))
        assertEquals(listOf(BN to at("2026-11-08T10:00:00Z"), BN to at("2026-11-08T10:00:05Z")), gaps)
    }

    @Test
    fun `rebuild replaces open buckets with source pieces plus buffered seconds and hands closed ones on as late`() {
        val b = secondBookAt("2026-11-08T10:03:05Z")
        // 10:02:30에 처음 받음(재시작). 다시 맞추기 결과가 오기 전에 10:03:00 초가 1분봉 10:02 칸을 그때까지 받은 값으로 닫는다.
        b.onSecondCandle(sec("2026-11-08T10:02:30Z", "110", "110", "110", "110", "1", "110"))
        for (s in 31..59) b.onSecondCandle(quiet("2026-11-08T10:02:${s}Z", "110"))
        b.onSecondCandle(sec("2026-11-08T10:03:00Z", "120", "120", "120", "120", "1", "120"))
        assertEquals(listOf(BN to at("2026-11-08T10:02:30Z")), gaps)
        assertCandle(closed.single { it.unit == CandleUnit.M1 }, "110", "110", "110", "110", "1", "110")
        b.drainUpdates()

        val minutes = listOf(
            Candle(BN, CandleUnit.M1, at("2026-11-08T10:00:00Z"), d("100"), d("105"), d("95"), d("101"), d("2"), d("200")),
            Candle(BN, CandleUnit.M1, at("2026-11-08T10:01:00Z"), d("101"), d("108"), d("100"), d("107"), d("3"), d("300")),
        )
        val seconds = listOf(
            sec("2026-11-08T10:02:00Z", "107", "109", "106", "108", "1", "108").candle,
            quiet("2026-11-08T10:02:10Z", "108").candle,
        )
        b.applyRebuild(BN, at("2026-11-08T10:02:30Z"), minutes, seconds)

        // 지금 열린 칸: 조각 + 받아 둔 초
        assertCandle(b.current(BN, CandleUnit.M5), "100", "120", "95", "120", "8", "838")
        assertCandle(b.current(BN, CandleUnit.M240), "100", "120", "95", "120", "8", "838")
        assertCandle(b.current(BN, CandleUnit.M1), "120", "120", "120", "120", "1", "120")
        assertTrue(b.drainUpdates().any { it.unit == CandleUnit.M5 && it.state == CandleState.LIVE })
        // 그사이 마감된 칸: 다시 계산한 값을 늦은 값으로
        assertCandle(lates.single { it.unit == CandleUnit.M1 }, "107", "110", "106", "110", "2", "218")
        assertEquals(at("2026-11-08T10:02:00Z"), lates.single { it.unit == CandleUnit.M1 }.start)
        assertCandle(lates.single { it.unit == CandleUnit.M3 }, "100", "110", "95", "110", "7", "718")

        // 같은 cut으로 다시 와도(이미 반영함) 아무것도 바꾸지 않는다
        b.applyRebuild(BN, at("2026-11-08T10:02:30Z"), emptyList(), emptyList())
        assertCandle(b.current(BN, CandleUnit.M5), "100", "120", "95", "120", "8", "838")
    }

    @Test
    fun `rebuild for an older cut is ignored after a newer gap`() {
        val b = secondBookAt("2026-11-08T10:00:00Z")
        b.onSecondCandle(sec("2026-11-08T10:00:10Z", "100", "100", "100", "100", "1", "100"))
        b.onSecondCandle(sec("2026-11-08T10:00:20Z", "100", "100", "100", "100", "1", "100"))
        assertEquals(2, gaps.size)
        val stale = Candle(BN, CandleUnit.M1, at("2026-11-08T08:00:00Z"), d("1"), d("1"), d("1"), d("1"), d("5"), d("5"))
        b.applyRebuild(BN, at("2026-11-08T10:00:10Z"), listOf(stale), emptyList())
        assertCandle(b.current(BN, CandleUnit.M240), "100", "100", "100", "100", "2", "200")
    }

    /** 테스트가 직접 움직이는 시계. 초를 넣을 때마다 그 초로 옮겨 칸이 끝나지 않게 한다. */
    private class MovingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    /** cut(10:00:00)부터 [n]초를 이어서 넣는다. k번째 초는 가격 100+k, 거래량 1. 시계는 넣는 초를 따라간다. */
    private fun feedSeconds(b: LiveCandleBook, clock: MovingClock, from: Int, until: Int) {
        for (k in from until until) {
            val t = at("2026-11-08T10:00:00Z").plusSeconds(k.toLong())
            clock.now = t
            val p = (100 + k).toString()
            b.onSecondCandle(SecondCandleEvent(SecondCandle(BN, t, d(p), d(p), d(p), d(p), d("1"), d(p))))
        }
    }

    /** 다시 맞추기 결과로 오면 4시간 칸(08:00)에 거래량 5를 더할 1분봉. */
    private val earlyMinute = Candle(BN, CandleUnit.M1, at("2026-11-08T08:00:00Z"), d("1"), d("1"), d("1"), d("1"), d("5"), d("5"))

    @Test
    fun `rebuild still applies with exactly the buffer limit of seconds waiting`() {
        val clock = MovingClock(at("2026-11-08T10:00:00Z"))
        val b = LiveCandleBook(clock, Duration.ofSeconds(10), { i, cut -> gaps += i to cut }) { c, late -> if (late) lates += c else closed += c }
        feedSeconds(b, clock, 0, LiveCandleBook.REBUILD_BUFFER_LIMIT)   // 10:00:00 ~ 10:09:59, 600개
        b.applyRebuild(BN, at("2026-11-08T10:00:00Z"), listOf(earlyMinute), emptyList())
        // 1분봉 조각(08:00)이 먼저, 그다음 초 600개: 시가는 조각 값, 거래량 5 + 600
        assertCandle(b.current(BN, CandleUnit.M240), "1", "699", "1", "699", "605", "${5 + (100..699).sum()}")
    }

    @Test
    fun `rebuild is abandoned once more than the buffer limit of seconds wait but the open buckets keep moving`() {
        val clock = MovingClock(at("2026-11-08T10:00:00Z"))
        val b = LiveCandleBook(clock, Duration.ofSeconds(10), { i, cut -> gaps += i to cut }) { c, late -> if (late) lates += c else closed += c }
        // 601번째 초(10:10:00)에서 모아 둔 초가 600개를 넘는다. 그 뒤로도 19초를 더 받는다(10:10:19까지, 모두 620개).
        feedSeconds(b, clock, 0, 620)
        assertEquals(listOf(BN to at("2026-11-08T10:00:00Z")), gaps)   // 포기해도 끊김으로 보지 않는다(초는 이어져 있다)

        // 진행 중 칸은 받은 초를 마지막 초까지 담는다.
        val all = (100..719).sum().toString()
        assertCandle(b.current(BN, CandleUnit.M240), "100", "719", "100", "719", "620", all)
        assertCandle(b.current(BN, CandleUnit.M60), "100", "719", "100", "719", "620", all)
        assertCandle(b.current(BN, CandleUnit.M1), "700", "719", "700", "719", "20", "${(700..719).sum()}")
        assertEquals(at("2026-11-08T10:10:00Z"), b.current(BN, CandleUnit.M1)!!.start)
        val before = LiveCandleBook.MINUTE_UNITS.associateWith { b.current(BN, it)!! }
        b.drainUpdates()
        lates.clear()

        // 포기한 뒤 같은 cut의 결과가 와도 칸을 바꾸지 않는다.
        b.applyRebuild(BN, at("2026-11-08T10:00:00Z"), listOf(earlyMinute), emptyList())
        for (u in LiveCandleBook.MINUTE_UNITS) assertEquals(before[u], b.current(BN, u), "$u")
        assertTrue(b.drainUpdates().isEmpty())
        assertTrue(lates.isEmpty())
    }
}
