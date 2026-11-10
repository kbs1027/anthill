package com.anthill.binance.stream

import com.anthill.binance.rest.TradingDay
import com.anthill.contract.Candle
import com.anthill.contract.CandleMerge
import com.anthill.contract.CandleUnit
import com.anthill.contract.TickerSnapshot
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * 종목별 UTC 당일 일봉을 1초 캔들로 이어 만든다. 업비트 티커와 같은 [TickerSnapshot]으로 내보내
 * marketdata가 일·주·월봉을 업비트와 같은 길(`onTicker`)로 처리하게 한다.
 *
 * 시작점은 REST `ticker/tradingDay` 값([anchor])이다. 그 값에 이미 들어 있는 체결(번호 ≤ `lastId`)은 다시 더하지 않는다.
 * REST 값이 만들어진 뒤 도착한 초를 놓치지 않도록 최근 [replayWindow] 동안 받은 초를 기억했다가 앵커 때 다시 더한다.
 * 앵커 순간에 걸친 초(첫 체결 ≤ lastId < 마지막 체결)는 나눌 수 없어 건너뛴다. 10분마다 다시 앵커해 차이를 없앤다.
 */
class DayBook(private val replayWindow: Duration = Duration.ofSeconds(15)) {

    private class Day(var candle: Candle, var lastId: Long)

    private val lock = Any()
    private val days = HashMap<String, Day>()
    private val recent = HashMap<String, ArrayDeque<BinanceKlineMsg>>()
    private var amounts: Map<String, BigDecimal> = emptyMap()
    @Volatile private var anchored = false

    /** 한 번이라도 앵커했으면 true. 그 전에는 REST로 티커를 만든다. */
    val ready: Boolean get() = anchored

    /** 24시간 거래대금(USDT)을 바꾼다. 다음 티커부터 이 값이 들어간다. */
    fun setAmounts(latest: Map<String, BigDecimal>) = synchronized(lock) { amounts = latest }

    /** REST 당일 값으로 바꾸고, 그 뒤에 받은 초(첫 체결 번호 > lastId)를 다시 더한다. 이미 더 새 날짜면 건너뛴다. */
    fun anchor(tradingDays: List<TradingDay>) = synchronized(lock) {
        for (td in tradingDays) {
            val current = days[td.symbol]
            if (current != null && current.candle.start > td.day.start) continue
            val day = Day(td.day, td.lastId)
            for (m in recent[td.symbol].orEmpty()) {
                if (m.firstTradeId > td.lastId) add(day, m)
            }
            days[td.symbol] = day
        }
        anchored = true
    }

    /** 1초 캔들 하나를 당일 일봉에 더한다. 일봉이 바뀌었으면 새 티커를, 아니면 null을 돌려준다. */
    fun onSecond(m: BinanceKlineMsg): TickerSnapshot? = synchronized(lock) {
        val symbol = m.second.instrument.symbol
        remember(symbol, m)
        val day = days[symbol] ?: return null
        val before = day.candle
        if (!add(day, m)) return null
        if (day.candle == before) return null
        ticker(day.candle, amounts[symbol])
    }

    /** 앵커된 종목 전체의 지금 티커. */
    fun snapshots(): List<TickerSnapshot> = synchronized(lock) {
        days.entries.sortedBy { it.key }.map { (symbol, day) -> ticker(day.candle, amounts[symbol]) }
    }

    /** 반영했으면 true. */
    private fun add(day: Day, m: BinanceKlineMsg): Boolean {
        val dayStart = CandleUnit.DAY.floor(m.second.start)
        if (dayStart < day.candle.start) return false
        if (dayStart > day.candle.start) {
            // 날짜가 바뀌었다: 이 초로 새 일봉을 연다(거래 없는 초면 o=h=l=c=직전 종가, 거래량 0).
            day.candle = m.second.toCandle(CandleUnit.DAY, dayStart)
            return true
        }
        if (m.lastTradeId <= day.lastId) return false // REST 값에 이미 들어 있음(거래 없는 초도 여기서 걸러짐)
        if (m.firstTradeId <= day.lastId) return false // 앵커에 걸친 초
        day.candle = CandleMerge.merge(day.candle, m.second.toCandle(CandleUnit.DAY, dayStart))
        return true
    }

    private fun remember(symbol: String, m: BinanceKlineMsg) {
        val q = recent.getOrPut(symbol) { ArrayDeque() }
        q.addLast(m)
        val oldest = m.second.start.minus(replayWindow)
        while (q.isNotEmpty() && q.first().second.start < oldest) q.removeFirst()
    }

    companion object {
        /** `signedChangeRate = (종가 − 당일 시가) / 시가`, 소수 8자리. */
        fun ticker(day: Candle, amount24h: BigDecimal?): TickerSnapshot {
            val rate = if (day.open.signum() == 0) BigDecimal.ZERO else (day.close - day.open).divide(day.open, 8, RoundingMode.HALF_UP)
            return TickerSnapshot(day.instrument, day.close, rate, amount24h ?: BigDecimal.ZERO, day)
        }

        /** REST 값만으로 티커를 만든다(스트림이 아직 준비되지 않았을 때). */
        fun tickers(tradingDays: List<TradingDay>, amounts: Map<String, BigDecimal>): List<TickerSnapshot> =
            tradingDays.map { ticker(it.day, amounts[it.symbol]) }
    }
}
