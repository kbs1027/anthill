package com.anthill.binance.rest

import com.anthill.binance.rest.BinanceJson.elements
import com.anthill.contract.Candle
import com.anthill.contract.CandleMerge
import com.anthill.contract.CandleUnit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 실제 바이낸스 응답(ZILUSDT, 거래가 드문 종목)으로 합치는 규칙이 바이낸스 값과 같은지 본다. */
class KlineMergeFixtureTest {

    private fun rows(name: String) = BinanceJson.mapper.readTree(FixtureServer.fixture(name)).elements()

    private fun merge(unit: CandleUnit, pieces: List<Candle>): Candle {
        val bucket = unit.floor(pieces.first().start)
        return pieces.drop(1).fold(pieces.first().copy(unit = unit, start = bucket)) { acc, p -> CandleMerge.merge(acc, p) }
    }

    @Test
    fun `sixty real seconds merge into the real one minute candle`() {
        val seconds = rows("klines-zilusdt-1s.json").map { BinanceJson.second(it, "ZILUSDT") }
        val minute = BinanceJson.candle(rows("klines-zilusdt-1m.json").first(), "ZILUSDT", CandleUnit.M1)
        assertEquals(60, seconds.size)
        assertEquals(minute.start, seconds.first().start)
        val merged = merge(CandleUnit.M1, seconds.map { it.toCandle(CandleUnit.M1, minute.start) })
        assertTrue(merged.sameValuesAs(minute), "$merged\n$minute")
        // 이 분은 첫 초에 거래가 없어서, 첫 조각 시가를 쓰면 틀린다(규칙이 필요한 이유).
        assertNotEquals(0, seconds.first().open.compareTo(minute.open))
    }

    @Test
    fun `fifteen real minutes merge into the real fifteen minute candle`() {
        val minutes = rows("klines-zilusdt-1m.json").map { BinanceJson.candle(it, "ZILUSDT", CandleUnit.M1) }
        val quarter = BinanceJson.candle(rows("klines-zilusdt-15m.json").single(), "ZILUSDT", CandleUnit.M15)
        assertEquals(15, minutes.size)
        val merged = merge(CandleUnit.M15, minutes)
        assertTrue(merged.sameValuesAs(quarter), "$merged\n$quarter")
    }
}
