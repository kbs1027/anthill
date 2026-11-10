package com.anthill.marketdata.candle

import com.anthill.contract.CandleUnit
import com.anthill.contract.Decimals
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.marketdata.FakeSource
import com.anthill.marketdata.PgTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CandleStorageTest : PgTest() {

    @Autowired lateinit var candles: CandleRepository
    @Autowired lateinit var coverage: CoverageRepository
    @Autowired lateinit var instruments: InstrumentRepository

    private val btc = FakeSource.BTC
    private val t0 = Instant.parse("2026-11-05T00:00:00Z")
    private fun t(min: Long) = t0.plusSeconds(min * 60)

    @Test
    fun `numeric round trip keeps value`() {
        val c = FakeSource.candle(t0).copy(volume = BigDecimal("0.00012345"), amount = BigDecimal("41983565546.3922900000000000"))
        candles.upsert(listOf(c))
        val back = candles.findRange(btc, CandleUnit.M1, t0, t(1)).single()
        assertTrue(Decimals.same(BigDecimal("0.00012345"), back.volume))
        assertTrue(back.sameValuesAs(c))
    }

    @Test
    fun `upsert replaces existing row`() {
        candles.upsert(listOf(FakeSource.candle(t0, close = "100")))
        candles.upsert(listOf(FakeSource.candle(t0, close = "105")))
        assertTrue(Decimals.same(BigDecimal("105"), candles.findRange(btc, CandleUnit.M1, t0, t(1)).single().close))
    }

    @Test
    fun `findBefore returns latest N ascending`() {
        candles.upsert(FakeSource.series(t0, 10))
        val got = candles.findBefore(btc, CandleUnit.M1, t(8), 3)
        assertEquals(listOf(t(5), t(6), t(7)), got.map { it.start })
    }

    @Test
    fun `delete removes only given starts`() {
        candles.upsert(FakeSource.series(t0, 3))
        candles.delete(btc, CandleUnit.M1, listOf(t(1)))
        assertEquals(listOf(t0, t(2)), candles.findRange(btc, CandleUnit.M1, t0, t(3)).map { it.start })
    }

    @Test
    fun `coverage merges adjacent and overlapping`() {
        coverage.add(btc, CandleUnit.M1, t(0), t(10))
        coverage.add(btc, CandleUnit.M1, t(10), t(20))
        coverage.add(btc, CandleUnit.M1, t(15), t(30))
        assertEquals(t(0), coverage.start(btc, CandleUnit.M1))
        assertEquals(t(30), coverage.end(btc, CandleUnit.M1))
        assertEquals(1, jdbc.sql("select count(*) from coverage").query(Long::class.java).single().toInt())
    }

    @Test
    fun `first coverage added at the same time from many threads does not collide`() {
        // 행이 아직 없을 때 과거 채우기와 화면 요청이 같은 구간을 동시에 넣던 경우(E2E에서 월봉 500으로 드러남).
        repeat(5) { round ->
            val unit = CandleUnit.entries[round]
            val start = java.util.concurrent.CountDownLatch(1)
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads = (0 until 8).map {
                Thread.ofVirtual().start {
                    start.await()
                    runCatching { coverage.add(btc, unit, t(0), t(10)) }.onFailure { errors += it }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertTrue(errors.isEmpty(), "동시 추가에서 오류: ${errors.firstOrNull()}")
            assertEquals(t(0), coverage.start(btc, unit))
            assertEquals(t(10), coverage.end(btc, unit))
        }
        assertEquals(5, jdbc.sql("select count(*) from coverage").query(Long::class.java).single().toInt())
    }

    @Test
    fun `coveredStartFor uses exclusive start inclusive end`() {
        coverage.add(btc, CandleUnit.M1, t(10), t(20))
        assertEquals(t(10), coverage.coveredStartFor(btc, CandleUnit.M1, t(20)))
        assertEquals(t(10), coverage.coveredStartFor(btc, CandleUnit.M1, t(15)))
        assertNull(coverage.coveredStartFor(btc, CandleUnit.M1, t(10)))
        assertNull(coverage.coveredStartFor(btc, CandleUnit.M1, t(21)))
    }

    @Test
    fun `instrument sync deactivates missing but keeps row`() {
        val xrp = InstrumentId.parse("UPBIT:KRW-XRP")
        assertTrue(instruments.sync("UPBIT", listOf(InstrumentInfo(btc, "비트코인", "Bitcoin"), InstrumentInfo(xrp, "리플", "XRP")), t0))
        assertFalse(instruments.sync("UPBIT", listOf(InstrumentInfo(btc, "비트코인", "Bitcoin"), InstrumentInfo(xrp, "리플", "XRP")), t(1)))
        assertTrue(instruments.sync("UPBIT", listOf(InstrumentInfo(btc, "비트코인", "Bitcoin")), t(2)))
        assertEquals(listOf(btc), instruments.active().map { it.id })
        assertTrue(instruments.exists(xrp))
    }
}
