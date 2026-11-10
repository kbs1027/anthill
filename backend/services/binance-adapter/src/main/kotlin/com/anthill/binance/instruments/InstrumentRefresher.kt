package com.anthill.binance.instruments

import com.anthill.binance.rest.BinanceJson
import com.anthill.binance.rest.BinanceRestClient
import com.anthill.binance.stream.StreamControl
import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.InstrumentListEvent
import com.anthill.eventbus.EventPublisher

/** 바이낸스 USDT 종목 목록을 다시 받아, 바뀌었을 때만 다시 구독하고 `market.instrument`를 보낸다. */
class InstrumentRefresher(
    private val rest: BinanceRestClient,
    private val stream: StreamControl,
    private val publisher: EventPublisher,
) {
    @Volatile private var current: List<InstrumentInfo> = emptyList()

    /** 지금 목록의 심볼(예: `BTCUSDT`). */
    fun currentSymbols(): List<String> = current.map { it.id.symbol }

    fun refresh(): Boolean {
        val latest = rest.markets()
        if (latest.toSet() == current.toSet()) return false
        current = latest
        stream.resubscribe(currentSymbols())
        publisher.publish(EventTypes.MARKET_INSTRUMENT, BinanceJson.SOURCE, InstrumentListEvent(BinanceJson.SOURCE, latest))
        return true
    }
}
