package com.anthill.marketdata

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import com.anthill.contract.SecondCandle
import com.anthill.contract.StreamStatus
import com.anthill.contract.TickerSnapshot
import com.anthill.marketdata.source.SourceClient
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 업비트 REST 의미(`to` 배타적, 최신→과거, 최대 200개, 상장 이전 빈 배열)를 흉내 내고 호출 수를 센다. */
class FakeSource : SourceClient {
    private val data = ConcurrentHashMap<Pair<InstrumentId, CandleUnit>, MutableList<Candle>>()
    private val calls = ConcurrentHashMap<Pair<InstrumentId, CandleUnit>, AtomicInteger>()
    @Volatile var instruments: List<InstrumentInfo> = emptyList()
    @Volatile var tickers: List<TickerSnapshot> = emptyList()
    @Volatile var status = StreamStatus(false, null)
    @Volatile var failures = 0
    /** 이 소스들은 종목·티커·상태 조회가 늘 실패한다(어댑터가 안 뜬 소스). */
    @Volatile var downSources: Set<String> = emptySet()
    /** 다음 n번의 캔들 조회를 실패시킨다(어댑터 REST가 아직 안 열렸을 때처럼). */
    val candleFailures = java.util.concurrent.atomic.AtomicInteger()
    /** 설정하면 candles()가 이 래치가 풀릴 때까지 기다린다(동시 실행 시험용). */
    @Volatile var gate: java.util.concurrent.CountDownLatch? = null
    private val secondData = ConcurrentHashMap<InstrumentId, MutableList<SecondCandle>>()
    /** seconds() 호출 기록: (종목, from, to). */
    val secondCalls: MutableList<Triple<InstrumentId, Instant, Instant>> = java.util.Collections.synchronizedList(mutableListOf())
    /** 다음 n번의 1초 캔들 조회를 실패시킨다. */
    val secondFailures = AtomicInteger()

    fun putSeconds(seconds: Collection<SecondCandle>) = seconds.forEach { s ->
        val list = secondData.getOrPut(s.instrument) { mutableListOf() }
        synchronized(list) {
            list.removeIf { it.start == s.start }
            list += s
        }
    }

    fun put(candles: Collection<Candle>) = candles.forEach { c ->
        val list = data.getOrPut(c.instrument to c.unit) { mutableListOf() }
        synchronized(list) {
            list.removeIf { it.start == c.start }
            list += c
        }
    }

    fun remove(i: InstrumentId, u: CandleUnit, start: Instant) = data[i to u]?.let { synchronized(it) { it.removeIf { c -> c.start == start } } }

    fun callCount(i: InstrumentId, u: CandleUnit) = calls[i to u]?.get() ?: 0
    fun totalCalls() = calls.values.sumOf { it.get() }
    fun reset() {
        data.clear(); calls.clear(); instruments = emptyList(); tickers = emptyList(); status = StreamStatus(false, null); failures = 0; candleFailures.set(0); gate = null
        secondData.clear(); secondCalls.clear(); secondFailures.set(0); downSources = emptySet()
    }

    override fun candles(instrument: InstrumentId, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle> {
        calls.getOrPut(instrument to unit) { AtomicInteger() }.incrementAndGet()
        gate?.await()
        if (candleFailures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw java.net.ConnectException("source down")
        val list = data[instrument to unit] ?: return emptyList()
        return synchronized(list) { list.filter { to == null || it.start < to }.sortedByDescending { it.start }.take(count) }
    }

    /** 바이낸스 `/internal/seconds` 의미: 과거→최신, [from] 포함·[to] 배타, 최대 1000초. */
    override fun seconds(instrument: InstrumentId, from: Instant, to: Instant, priority: Priority): List<SecondCandle> {
        secondCalls += Triple(instrument, from, to)
        if (secondFailures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw java.net.ConnectException("source down")
        val list = secondData[instrument] ?: return emptyList()
        return synchronized(list) { list.filter { it.start >= from && it.start < to }.sortedBy { it.start }.take(1000) }
    }

    override fun instruments(source: String): List<InstrumentInfo> { failIfAsked(source); return instruments.filter { it.id.source == source } }
    override fun tickers(source: String): List<TickerSnapshot> { failIfAsked(source); return tickers.filter { it.instrument.source == source } }
    override fun streamStatus(source: String): StreamStatus { failIfAsked(source); return status }

    private fun failIfAsked(source: String) {
        if (source in downSources) error("source $source down")
        if (failures > 0) { failures--; error("source down") }
    }

    companion object {
        val BTC = InstrumentId.parse("UPBIT:KRW-BTC")
        val BN = InstrumentId.parse("BINANCE:BTCUSDT")

        fun candle(start: Instant, close: String = "100", unit: CandleUnit = CandleUnit.M1, instrument: InstrumentId = BTC, volume: String = "1") = Candle(
            instrument, unit, start, BigDecimal("100"), BigDecimal(close).max(BigDecimal("100")), BigDecimal("90").min(BigDecimal(close)),
            BigDecimal(close), BigDecimal(volume), BigDecimal(close).multiply(BigDecimal(volume)),
        )

        /** [from]부터 [step]분 간격으로 [n]개. */
        fun series(from: Instant, n: Int, stepMinutes: Long = 1, unit: CandleUnit = CandleUnit.M1) =
            (0 until n).map { candle(from.plusSeconds(it * stepMinutes * 60), close = (100 + it % 7).toString(), unit = unit) }
    }
}
