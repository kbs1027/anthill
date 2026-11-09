package com.anthill.upbit.instruments

import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.InstrumentListEvent
import com.anthill.eventbus.EventPublisher
import com.anthill.upbit.rest.UpbitJson
import com.anthill.upbit.rest.UpbitRestClient
import com.anthill.upbit.stream.StreamControl

/** 업비트 KRW 종목 목록을 다시 받아, 바뀌었을 때만 다시 구독하고 `market.instrument`를 보낸다. */
class InstrumentRefresher(
    private val rest: UpbitRestClient,
    private val stream: StreamControl,
    private val publisher: EventPublisher,
) {
    @Volatile private var current: List<InstrumentInfo> = emptyList()

    fun currentMarkets(): List<String> = current.map { it.id.symbol }

    fun refresh(): Boolean {
        val latest = rest.markets()
        if (latest.toSet() == current.toSet()) return false
        current = latest
        stream.resubscribe(currentMarkets())
        publisher.publish(EventTypes.MARKET_INSTRUMENT, UpbitJson.SOURCE, InstrumentListEvent(UpbitJson.SOURCE, latest))
        return true
    }
}
