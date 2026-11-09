package com.anthill.contract

/** 소스 접두어를 붙인 종목 ID. 예: `UPBIT:KRW-BTC`, `KRX:005930`, `US:AAPL`. */
data class InstrumentId(val value: String) {
    init {
        val sep = value.indexOf(':')
        require(sep > 0 && sep < value.length - 1) { "instrument id must be SOURCE:SYMBOL: $value" }
    }

    val source: String get() = value.substringBefore(':')
    val symbol: String get() = value.substringAfter(':')

    override fun toString() = value

    companion object {
        fun of(source: String, symbol: String) = InstrumentId("$source:$symbol")
        fun parse(value: String) = InstrumentId(value)
    }
}
