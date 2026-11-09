package com.anthill.marketdata.api

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.marketdata.candle.CandleHistoryService
import com.anthill.marketdata.candle.InstrumentRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.format.DateTimeParseException

data class CandlePageResponse(val market: InstrumentId, val unit: CandleUnit, val candles: List<Candle>, val live: Candle?, val hasMore: Boolean)

@RestController
class CandleController(private val history: CandleHistoryService, private val instruments: InstrumentRepository) {

    @GetMapping("/api/candles")
    fun candles(
        @RequestParam market: String,
        @RequestParam unit: String,
        @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "200") count: Int,
    ): CandlePageResponse {
        val id = InstrumentId.parse(market)
        val candleUnit = requireNotNull(CandleUnit.fromCode(unit)) { "unknown unit: $unit" }
        require(count in 1..200) { "count must be 1..200: $count" }
        val before = to?.let(Instant::parse)
        if (!instruments.exists(id)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "unknown market $market")
        val page = history.page(id, candleUnit, before, count)
        return CandlePageResponse(id, candleUnit, page.candles, page.live, page.hasMore)
    }

    @ExceptionHandler(IllegalArgumentException::class, DateTimeParseException::class)
    fun badRequest(e: RuntimeException): ResponseEntity<Map<String, String?>> = ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
