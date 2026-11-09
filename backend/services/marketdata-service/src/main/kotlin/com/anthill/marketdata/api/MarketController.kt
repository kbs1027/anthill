package com.anthill.marketdata.api

import com.anthill.contract.InstrumentId
import com.anthill.marketdata.candle.InstrumentRepository
import com.anthill.marketdata.live.TickerCache
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

data class MarketDto(
    val market: InstrumentId,
    val koreanName: String,
    val englishName: String,
    val price: BigDecimal?,
    val changeRate: BigDecimal?,
    val amount24h: BigDecimal?,
)

@RestController
class MarketController(private val instruments: InstrumentRepository, private val tickers: TickerCache) {

    @GetMapping("/api/markets")
    fun markets(): List<MarketDto> = instruments.active()
        .map { info ->
            val t = tickers.get(info.id)
            MarketDto(info.id, info.koreanName, info.englishName, t?.tradePrice, t?.signedChangeRate, t?.accTradePrice24h)
        }
        .sortedWith(compareByDescending<MarketDto, BigDecimal?>(nullsFirst()) { it.amount24h })
}
