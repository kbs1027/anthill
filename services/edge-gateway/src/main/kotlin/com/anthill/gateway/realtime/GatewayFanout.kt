package com.anthill.gateway.realtime

import com.anthill.contract.Candle
import com.anthill.contract.CandleState
import com.anthill.contract.CandleUnit
import com.anthill.contract.CandleUpdateEvent
import com.anthill.contract.ContractJson
import com.anthill.contract.InstrumentId
import com.anthill.contract.TickerSnapshot
import java.math.BigDecimal
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger

data class CandleItem(val market: InstrumentId, val unit: CandleUnit, val state: CandleState, val time: Instant, val candle: Candle?)

data class TickerItem(val market: InstrumentId, val price: BigDecimal, val changeRate: BigDecimal, val amount24h: BigDecimal)

data class StreamMessage(val type: String, val items: List<Any>)

/**
 * 받은 이벤트를 구독 세션에 나눠 보낸다. [batch] 안에서 받은 이벤트는 모아 두었다가
 * 세션마다 메시지 하나({"type":"candles"} 하나, {"type":"tickers"} 하나)로 보낸다.
 */
class GatewayFanout(private val registry: SubscriptionRegistry, private val sender: (session: String, json: String) -> Unit) {
    private val log = Logger.getLogger(GatewayFanout::class.java.name)
    private val mapper = ContractJson.mapper()
    private val current = ThreadLocal<Batch>()

    private class Batch {
        val candles = ArrayList<CandleUpdateEvent>()
        val tickers = ArrayList<TickerSnapshot>()
    }

    fun <T> batch(block: () -> T): T {
        if (current.get() != null) return block()
        val b = Batch()
        current.set(b)
        try {
            return block()
        } finally {
            current.remove()
            deliver(b)
        }
    }

    fun onCandle(e: CandleUpdateEvent) {
        val b = current.get()
        if (b != null) b.candles += e else deliver(Batch().also { it.candles += e })
    }

    fun onTicker(t: TickerSnapshot) {
        val b = current.get()
        if (b != null) b.tickers += t else deliver(Batch().also { it.tickers += t })
    }

    private fun deliver(b: Batch) {
        if (b.candles.isNotEmpty()) {
            val perSession = LinkedHashMap<String, MutableList<CandleItem>>()
            for (e in b.candles) {
                val item = CandleItem(e.instrument, e.unit, e.state, e.start, e.candle)
                for (s in registry.sessionsFor(e.instrument, e.unit)) perSession.getOrPut(s) { ArrayList() } += item
            }
            perSession.forEach { (s, items) -> send(s, mapper.writeValueAsString(StreamMessage("candles", items))) }
        }
        if (b.tickers.isNotEmpty()) {
            val sessions = registry.tickerSessions()
            if (sessions.isNotEmpty()) {
                val json = mapper.writeValueAsString(
                    StreamMessage("tickers", b.tickers.map { TickerItem(it.instrument, it.tradePrice, it.signedChangeRate, it.accTradePrice24h) }),
                )
                sessions.forEach { send(it, json) }
            }
        }
    }

    private fun send(session: String, json: String) = try {
        sender(session, json)
    } catch (e: Exception) {
        log.log(Level.FINE, "send to $session failed", e)
    }
}
