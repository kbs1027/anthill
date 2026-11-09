package com.anthill.marketdata.live

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.TickerSnapshot
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** 어댑터 두 대의 시세가 조금씩 다른 순서로 온다. 느린 쪽의 옛 티커가 값을 되돌리면 안 된다. 하루 누적 거래량은 줄지 않으므로 그것으로 가른다. */
class SlowerFeedTest {

    private val btc = InstrumentId.parse("UPBIT:KRW-BTC")
    private val day = Instant.parse("2026-11-08T00:00:00Z")
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:20Z"), ZoneOffset.UTC)

    private fun ticker(close: Long, volume: Long): TickerSnapshot {
        val c = BigDecimal(close)
        return TickerSnapshot(btc, c, BigDecimal.ZERO, BigDecimal(volume), Candle(btc, CandleUnit.DAY, day, c, c, c, c, BigDecimal(volume), BigDecimal(volume)))
    }

    @Test
    fun `an older ticker does not move the day candle back`() {
        val book = LiveCandleBook(clock, Duration.ofSeconds(10)) { _, _ -> }
        book.onTicker(ticker(200, 20))
        book.onTicker(ticker(100, 10))
        assertEquals(BigDecimal(200), book.current(btc, CandleUnit.DAY)!!.close)
    }

    @Test
    fun `a newer ticker still applies`() {
        val book = LiveCandleBook(clock, Duration.ofSeconds(10)) { _, _ -> }
        book.onTicker(ticker(100, 10))
        book.onTicker(ticker(200, 20))
        assertEquals(BigDecimal(200), book.current(btc, CandleUnit.DAY)!!.close)
    }

    @Test
    fun `the ticker list ignores an older ticker too`() {
        val cache = TickerCache()
        cache.put(ticker(200, 20))
        cache.drainChanged()
        cache.put(ticker(100, 10))
        assertEquals(BigDecimal(200), cache.get(btc)!!.tradePrice)
        assertEquals(emptyList(), cache.drainChanged())
    }

    @Test
    fun `the ticker list accepts a new day with a smaller volume`() {
        val cache = TickerCache()
        cache.put(ticker(200, 20))
        val next = Instant.parse("2026-11-09T00:00:00Z")
        val c = BigDecimal(150)
        cache.put(TickerSnapshot(btc, c, BigDecimal.ZERO, BigDecimal.ONE, Candle(btc, CandleUnit.DAY, next, c, c, c, c, BigDecimal.ONE, BigDecimal.ONE)))
        assertEquals(BigDecimal(150), cache.get(btc)!!.tradePrice)
    }
}
