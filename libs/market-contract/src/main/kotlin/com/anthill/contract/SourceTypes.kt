package com.anthill.contract

import java.math.BigDecimal
import java.time.Instant

data class InstrumentInfo(val id: InstrumentId, val koreanName: String, val englishName: String)

/** 소스의 현재가 스냅샷. [day]는 티커 값으로 만든 당일 일봉(업비트에서 일봉과 수치상 같음). */
data class TickerSnapshot(
    val instrument: InstrumentId,
    val tradePrice: BigDecimal,
    val signedChangeRate: BigDecimal,
    val accTradePrice24h: BigDecimal,
    val day: Candle,
)

data class StreamStatus(val connected: Boolean, val since: Instant?)
