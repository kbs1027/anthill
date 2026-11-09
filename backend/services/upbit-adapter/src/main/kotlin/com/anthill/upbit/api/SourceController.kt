package com.anthill.upbit.api

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import com.anthill.contract.StreamStatus
import com.anthill.contract.TickerSnapshot
import com.anthill.upbit.rest.HttpUpbitRestClient
import com.anthill.upbit.rest.UpbitRestClient
import com.anthill.upbit.stream.StreamControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException

/** 어댑터 공통 계약의 소스 조회 API (동기). marketdata가 부른다. */
@RestController
@RequestMapping("/internal")
class SourceController(private val rest: UpbitRestClient, private val stream: StreamControl) {

    @GetMapping("/candles")
    fun candles(
        @RequestParam instrument: String,
        @RequestParam unit: String,
        @RequestParam(required = false) to: String?,
        @RequestParam count: Int,
        @RequestParam(defaultValue = "BACKFILL") priority: Priority,
    ): List<Candle> {
        val id = InstrumentId.parse(instrument)
        require(id.source == HttpUpbitRestClient.SOURCE) { "not an upbit instrument: $instrument" }
        val candleUnit = requireNotNull(CandleUnit.fromCode(unit)) { "unknown unit: $unit" }
        require(count in 1..200) { "count must be 1..200: $count" }
        return rest.candles(id.symbol, candleUnit, to?.let(Instant::parse), count, priority)
    }

    @GetMapping("/instruments")
    fun instruments(): List<InstrumentInfo> = rest.markets()

    @GetMapping("/tickers")
    fun tickers(): List<TickerSnapshot> = rest.tickers()

    @GetMapping("/stream-status")
    fun streamStatus(): StreamStatus = stream.status

    @ExceptionHandler(IllegalArgumentException::class, DateTimeParseException::class)
    fun badRequest(e: RuntimeException): ResponseEntity<Map<String, String?>> = ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
