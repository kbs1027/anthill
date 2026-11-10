package com.anthill.contract

/**
 * 작은 캔들 조각을 큰 캔들에 더하는 규칙(바이낸스와 같은 방식, 실제 데이터로 확인).
 *
 * - 거래가 없던 조각(거래량 0)은 값을 바꾸지 않는다. 그런 조각의 시·고·저·종은 직전 종가라 종가도 그대로다.
 * - 아직 거래가 없던 칸(거래량 0)에 거래 있는 조각이 오면 시·고·저·종을 그 조각 값으로 바꾼다.
 *   그래서 큰 캔들의 시가는 "첫 거래가 있는 조각의 시가"가 된다.
 * - 그 밖에는 고가 최대, 저가 최소, 종가는 새 조각, 거래량·거래대금은 더한다.
 *
 * 결과는 [acc]의 종목·단위·시작 시각을 그대로 쓴다.
 */
object CandleMerge {
    fun merge(acc: Candle, piece: Candle): Candle {
        if (piece.volume.signum() == 0) return acc
        if (acc.volume.signum() == 0) {
            return acc.copy(
                open = piece.open, high = piece.high, low = piece.low, close = piece.close,
                volume = piece.volume, amount = piece.amount,
            )
        }
        return acc.copy(
            high = acc.high.max(piece.high),
            low = acc.low.min(piece.low),
            close = piece.close,
            volume = acc.volume + piece.volume,
            amount = acc.amount + piece.amount,
        )
    }
}
