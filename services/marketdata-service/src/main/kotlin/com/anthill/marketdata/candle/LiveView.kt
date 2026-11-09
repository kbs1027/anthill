package com.anthill.marketdata.candle

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import java.time.Instant

/** 과거 조회가 실시간 쪽에서 알아야 하는 것: 진행 중 캔들, 소스 스트림이 이어진 시작 시각. */
interface LiveView {
    fun current(i: InstrumentId, u: CandleUnit): Candle?
    fun streamConnectedSince(source: String): Instant?
}

/** 정정·삭제된 캔들을 실시간 쪽으로 넘기는 통로. */
fun interface CandleChangeSink {
    fun onCorrected(i: InstrumentId, u: CandleUnit, upserted: List<Candle>, deleted: List<Instant>)
}
