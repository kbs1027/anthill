package com.anthill.marketdata.live

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.SecondCandle
import com.anthill.marketdata.FakeSource
import com.anthill.marketdata.FakeSource.Companion.BN
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecondRebuilderTest {

    private val source = FakeSource()
    private data class Applied(val i: InstrumentId, val cut: Instant, val minutes: List<Candle>, val seconds: List<SecondCandle>)
    private val applied: MutableList<Applied> = Collections.synchronizedList(mutableListOf())
    private val appliedLatch = CountDownLatch(2)

    private fun at(s: String) = Instant.parse(s)

    /** 요청을 그 자리에서 처리한다(순서가 정해진 시험용). */
    private fun direct() = SecondRebuilder(source, { i, cut, m, s -> applied += Applied(i, cut, m, s) }, retryDelay = Duration.ofMillis(1), executor = Executor { it.run() })

    private fun minutes(from: Instant, n: Int) = (0 until n).map { FakeSource.candle(from.plusSeconds(it * 60L), instrument = BN) }

    private fun second(t: Instant) = SecondCandle(BN, t, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)

    @Test
    fun `asks minutes from the four hour start to the minute start and seconds from the minute start to the cut`() {
        source.put(minutes(at("2026-11-08T08:00:00Z"), 240))   // 08:00 ~ 11:59
        source.putSeconds((0 until 59).map { second(at("2026-11-08T11:59:00Z").plusSeconds(it.toLong())) })
        direct().request(BN, at("2026-11-08T11:59:30Z"))

        val a = applied.single()
        assertEquals(at("2026-11-08T11:59:30Z"), a.cut)
        assertEquals(239, a.minutes.size)                                  // 08:00 ~ 11:58
        assertEquals(at("2026-11-08T08:00:00Z"), a.minutes.first().start)
        assertEquals(at("2026-11-08T11:58:00Z"), a.minutes.last().start)
        assertEquals(a.minutes.sortedBy { it.start }, a.minutes)           // 과거→최신
        assertEquals(2, source.callCount(BN, CandleUnit.M1))               // 200개씩 두 번
        assertEquals(listOf(Triple(BN, at("2026-11-08T11:59:00Z"), at("2026-11-08T11:59:30Z"))), source.secondCalls)
        assertEquals(30, a.seconds.size)
    }

    @Test
    fun `cut on a minute boundary asks no seconds`() {
        direct().request(BN, at("2026-11-08T08:00:00Z"))
        assertTrue(source.secondCalls.isEmpty())
        assertEquals(0, source.callCount(BN, CandleUnit.M1))   // 4시간 칸 시작과 같아 1분봉도 필요 없다
        assertEquals(1, applied.size)
    }

    @Test
    fun `failure is retried once`() {
        source.secondFailures.set(1)
        direct().request(BN, at("2026-11-08T10:00:30Z"))
        assertEquals(2, source.secondCalls.size)
        assertEquals(1, applied.size)
    }

    @Test
    fun `second failure gives up`() {
        source.secondFailures.set(2)
        direct().request(BN, at("2026-11-08T10:00:30Z"))
        assertEquals(2, source.secondCalls.size)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `requests for the same instrument while one is running are merged into the newest`() {
        val gate = CountDownLatch(1)
        source.gate = gate
        val rebuilder = SecondRebuilder(source, { i, cut, m, s -> applied += Applied(i, cut, m, s); appliedLatch.countDown() }, retryDelay = Duration.ofMillis(1))
        rebuilder.request(BN, at("2026-11-08T10:00:30Z"))
        val until = System.nanoTime() + 5_000_000_000L
        while (source.totalCalls() == 0 && System.nanoTime() < until) Thread.sleep(5)
        rebuilder.request(BN, at("2026-11-08T10:00:40Z"))
        rebuilder.request(BN, at("2026-11-08T10:00:50Z"))
        gate.countDown()
        assertTrue(appliedLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(listOf(at("2026-11-08T10:00:30Z"), at("2026-11-08T10:00:50Z")), applied.map { it.cut })
    }
}
