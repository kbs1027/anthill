package com.anthill.upbit.rest

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import com.anthill.contract.TickerSnapshot
import com.anthill.upbit.rest.UpbitJson.elements
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

interface UpbitRestClient {
    /** KRW 마켓 종목만. */
    fun markets(): List<InstrumentInfo>

    /** 업비트가 주는 그대로 최신→과거 순. [to]는 배타적, [count]는 1..200. */
    fun candles(market: String, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle>

    fun tickers(): List<TickerSnapshot>
}

class UpbitApiException(val status: Int, body: String) : RuntimeException("upbit responded $status: $body")

class HttpUpbitRestClient(
    private val baseUrl: String,
    private val budget: RequestBudget,
    private val http: HttpClient = HttpClient.newHttpClient(),
    private val retryBase: Duration = Duration.ofMillis(200),
    private val maxRetries: Int = 3,
) : UpbitRestClient {

    override fun markets(): List<InstrumentInfo> =
        get("market/all", Priority.RECONCILE).elements()
            .filter { it["market"].asString().startsWith("KRW-") }
            .map { InstrumentInfo(id(it["market"].asString()), it["korean_name"].asString(), it["english_name"].asString()) }

    override fun candles(market: String, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle> {
        require(count in 1..200) { "count must be 1..200: $count" }
        val path = when (unit) {
            CandleUnit.DAY -> "candles/days"
            CandleUnit.WEEK -> "candles/weeks"
            CandleUnit.MONTH -> "candles/months"
            else -> "candles/minutes/${unit.minutes}"
        }
        val query = buildString {
            append("market=").append(encode(market)).append("&count=").append(count)
            if (to != null) append("&to=").append(encode(TO_FORMAT.format(to)))
        }
        return get("$path?$query", priority).elements().map { UpbitJson.candle(it, market, unit) }
    }

    override fun tickers(): List<TickerSnapshot> =
        get("ticker/all?quote_currencies=KRW", Priority.RECONCILE).elements().map { UpbitJson.ticker(it, it["market"].asString()) }

    private fun get(pathAndQuery: String, priority: Priority): JsonNode {
        val request = HttpRequest.newBuilder(URI("$baseUrl/$pathAndQuery"))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        var attempt = 0
        while (true) {
            val response = budget.run(priority) { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            when {
                response.statusCode() in 200..299 -> return UpbitJson.mapper.readTree(response.body())
                response.statusCode() == 429 && attempt < maxRetries -> {
                    Thread.sleep(retryBase.multipliedBy(1L shl attempt))
                    attempt++
                }
                else -> throw UpbitApiException(response.statusCode(), response.body())
            }
        }
    }

    private fun id(market: String) = UpbitJson.id(market)

    private fun encode(v: String) = URLEncoder.encode(v, Charsets.UTF_8)

    companion object {
        const val SOURCE = UpbitJson.SOURCE
        private val TO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)
    }
}
