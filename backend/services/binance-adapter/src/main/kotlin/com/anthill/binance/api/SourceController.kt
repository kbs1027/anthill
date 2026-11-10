package com.anthill.binance.api

import com.anthill.binance.rest.BinanceJson
import com.anthill.binance.rest.BinanceRestClient
import com.anthill.binance.stream.DayBook
import com.anthill.binance.stream.StreamControl
import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import com.anthill.contract.SecondCandle
import com.anthill.contract.StreamStatus
import com.anthill.contract.TickerSnapshot
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** 어댑터 공통 계약의 소스 조회 API (동기). marketdata가 부른다. 모양은 업비트 어댑터와 같고 `/internal/seconds`가 더 있다. */
@RestController
@RequestMapping("/internal")
class SourceController(
    private val rest: BinanceRestClient,
    private val stream: StreamControl,
    private val dayBook: DayBook,
) {

    @GetMapping("/candles")
    fun candles(
        @RequestParam instrument: String,
        @RequestParam unit: String,
        @RequestParam(required = false) to: String?,
        @RequestParam count: Int,
        @RequestParam(defaultValue = "BACKFILL") priority: Priority,
    ): List<Candle> {
        val id = binance(instrument)
        val candleUnit = requireNotNull(CandleUnit.fromCode(unit)) { "unknown unit: $unit" }
        require(count in 1..200) { "count must be 1..200: $count" }
        return rest.candles(id.symbol, candleUnit, to?.let(Instant::parse), count, priority)
    }

    /** 1초 캔들, 과거→최신 순. [from] 포함, [to] 배타, 최대 1000초. */
    @GetMapping("/seconds")
    fun seconds(
        @RequestParam instrument: String,
        @RequestParam from: String,
        @RequestParam to: String,
        @RequestParam(defaultValue = "RECOVERY") priority: Priority,
    ): List<SecondCandle> {
        val id = binance(instrument)
        val start = Instant.parse(from)
        val end = Instant.parse(to)
        require(end > start) { "to must be after from: $from..$to" }
        require(Duration.between(start, end) <= Duration.ofSeconds(BinanceRestClient.MAX_SECONDS)) {
            "at most ${BinanceRestClient.MAX_SECONDS} seconds: $from..$to"
        }
        return rest.seconds(id.symbol, start, end, priority)
    }

    @GetMapping("/instruments")
    fun instruments(): List<InstrumentInfo> = rest.markets()

    /** 스트림으로 이어 만든 당일 값이 있으면 그것을, 아직 없으면 REST로 만든 값을 준다. */
    @GetMapping("/tickers")
    fun tickers(): List<TickerSnapshot> {
        if (dayBook.ready) return dayBook.snapshots()
        val symbols = rest.markets().map { it.id.symbol }
        return DayBook.tickers(rest.tradingDays(symbols), rest.amounts24h())
    }

    @GetMapping("/stream-status")
    fun streamStatus(): StreamStatus = stream.status

    private fun binance(instrument: String): InstrumentId {
        val id = InstrumentId.parse(instrument)
        require(id.source == BinanceJson.SOURCE) { "not a binance instrument: $instrument" }
        return id
    }

    @ExceptionHandler(IllegalArgumentException::class, DateTimeParseException::class)
    fun badRequest(e: RuntimeException): ResponseEntity<Map<String, String?>> = ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
