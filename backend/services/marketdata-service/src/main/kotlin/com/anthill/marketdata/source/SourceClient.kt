package com.anthill.marketdata.source

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import com.anthill.contract.SecondCandle
import com.anthill.contract.StreamStatus
import com.anthill.contract.TickerSnapshot
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** 어댑터 공통 계약(`/internal` 아래 조회 API)을 부르는 동기 클라이언트. 소스는 종목 ID의 접두어로 고른다. */
interface SourceClient {
    /** 최신→과거 순. [to]는 배타적. */
    fun candles(instrument: InstrumentId, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle>
    fun instruments(source: String): List<InstrumentInfo>
    fun tickers(source: String): List<TickerSnapshot>
    fun streamStatus(source: String): StreamStatus
    /** 1초 캔들(1초 캔들을 주는 소스만). 과거→최신 순, [from] 포함·[to] 배타, 최대 1000초. */
    fun seconds(instrument: InstrumentId, from: Instant, to: Instant, priority: Priority): List<SecondCandle>
}

class SourceUnavailableException(message: String) : RuntimeException(message)

class HttpSourceClient(
    private val baseUrls: Map<String, URI>,
    private val mapper: JsonMapper,
    private val http: HttpClient = HttpClient.newHttpClient(),
) : SourceClient {

    override fun candles(instrument: InstrumentId, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle> {
        val query = buildString {
            append("instrument=").append(enc(instrument.value)).append("&unit=").append(enc(unit.code))
            append("&count=").append(count).append("&priority=").append(priority.name)
            if (to != null) append("&to=").append(enc(to.toString()))
        }
        return get(instrument.source, "/internal/candles?$query", object : TypeReference<List<Candle>>() {})
    }

    override fun instruments(source: String): List<InstrumentInfo> =
        get(source, "/internal/instruments", object : TypeReference<List<InstrumentInfo>>() {})

    override fun tickers(source: String): List<TickerSnapshot> =
        get(source, "/internal/tickers", object : TypeReference<List<TickerSnapshot>>() {})

    override fun streamStatus(source: String): StreamStatus =
        get(source, "/internal/stream-status", object : TypeReference<StreamStatus>() {})

    override fun seconds(instrument: InstrumentId, from: Instant, to: Instant, priority: Priority): List<SecondCandle> {
        val query = "instrument=${enc(instrument.value)}&from=${enc(from.toString())}&to=${enc(to.toString())}&priority=${priority.name}"
        return get(instrument.source, "/internal/seconds?$query", object : TypeReference<List<SecondCandle>>() {})
    }

    private fun <T> get(source: String, path: String, type: TypeReference<T>): T {
        val base = baseUrls[source] ?: throw SourceUnavailableException("no adapter configured for $source")
        val request = HttpRequest.newBuilder(URI("$base$path")).timeout(Duration.ofSeconds(60)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw SourceUnavailableException("$source $path → ${response.statusCode()}")
        return mapper.readValue(response.body(), type)
    }

    private fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8)
}
