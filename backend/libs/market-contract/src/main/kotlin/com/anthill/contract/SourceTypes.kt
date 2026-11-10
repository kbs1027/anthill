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

/**
 * 1초 동안의 체결을 묶은 캔들(바이낸스 `kline_1s`). [start]는 그 초의 시작(UTC).
 * 거래가 없던 초는 거래량 0, 시·고·저·종 = 직전 종가로 온다.
 */
data class SecondCandle(
    val instrument: InstrumentId,
    val start: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val amount: BigDecimal,
) {
    /** [bucket]에서 시작하는 [unit] 칸의 한 조각으로 바꾼다. [CandleMerge.merge]로 칸에 더할 때 쓴다. */
    fun toCandle(unit: CandleUnit, bucket: Instant): Candle =
        Candle(instrument, unit, bucket, open, high, low, close, volume, amount)
}
