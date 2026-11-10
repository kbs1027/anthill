package com.anthill.binance

import com.anthill.binance.rest.BinanceRestClient
import com.anthill.binance.rest.TradingDay
import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import com.anthill.contract.SecondCandle
import java.math.BigDecimal
import java.time.Instant

/** 테스트용 바이낸스 REST 대역. 넣어 둔 값을 돌려주고 마지막 호출을 기록한다. */
open class FakeRest : BinanceRestClient {
    var markets: List<InstrumentInfo> = emptyList()
    var candles: List<Candle> = emptyList()
    var seconds: List<SecondCandle> = emptyList()
    var tradingDays: List<TradingDay> = emptyList()
    var amounts: Map<String, BigDecimal> = emptyMap()
    var lastCall: List<Any?> = emptyList()

    override fun markets() = markets
    override fun candles(symbol: String, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle> {
        lastCall = listOf(symbol, unit, to, count, priority)
        return candles
    }
    override fun seconds(symbol: String, from: Instant, to: Instant, priority: Priority): List<SecondCandle> {
        lastCall = listOf(symbol, from, to, priority)
        return seconds
    }
    override fun tradingDays(symbols: List<String>) = tradingDays.filter { it.symbol in symbols }
    override fun amounts24h() = amounts
}
