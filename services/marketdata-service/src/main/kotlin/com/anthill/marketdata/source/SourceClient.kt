package com.anthill.marketdata.source

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import com.anthill.contract.StreamStatus
import com.anthill.contract.TickerSnapshot
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 어댑터 공통 계약(`/internal` 아래 조회 API)을 부르는 동기 클라이언트. 소스는 종목 ID의 접두어로 고른다. */
interface SourceClient {
    /** 최신→과거 순. [to]는 배타적. */
    fun candles(instrument: InstrumentId, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle>
    fun instruments(source: String): List<InstrumentInfo>
    fun tickers(source: String): List<TickerSnapshot>
    fun streamStatus(source: String): StreamStatus
}

class SourceUnavailableException(message: String) : RuntimeException(message)

/**
 * [baseUrls]는 소스마다 어댑터 주소 목록이다. 응답이 없거나 5xx면 다음 어댑터로 넘어가고, 성공한 곳을 계속 쓴다.
 * 요청 예산(업비트 IP당 초당 10회)을 두 대가 함께 쓰지 않도록 한 번에 한 곳만 부른다.
 */
class HttpSourceClient(
    private val baseUrls: Map<String, List<URI>>,
    private val mapper: JsonMapper,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
) : SourceClient {

    private val preferred = ConcurrentHashMap<String, Int>()

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

    private fun <T> get(source: String, path: String, type: TypeReference<T>): T {
        val bases = baseUrls[source]?.takeIf { it.isNotEmpty() } ?: throw SourceUnavailableException("no adapter configured for $source")
        val first = preferred[source] ?: 0
        var failure: Exception? = null
        for (offset in bases.indices) {
            val index = (first + offset) % bases.size
            val request = HttpRequest.newBuilder(URI("${bases[index]}$path")).timeout(Duration.ofSeconds(60)).GET().build()
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                failure = e
                continue
            }
            val status = response.statusCode()
            if (status in 200..299) {
                preferred[source] = index
                return mapper.readValue(response.body(), type)
            }
            failure = SourceUnavailableException("$source $path → $status")
            if (status < 500) throw failure
        }
        throw failure!!
    }

    private fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8)
}
