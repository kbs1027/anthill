package com.anthill.upbit.rest

import com.anthill.contract.Candle
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import com.anthill.contract.TickerSnapshot
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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

    /** 업비트 숫자는 JSON 숫자로 온다. double을 거치지 않도록 BigDecimal로 바로 읽는다. */
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

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
        val instrument = id(market)
        return get("$path?$query", priority).elements().map { n ->
            Candle(
                instrument, unit,
                LocalDateTime.parse(n["candle_date_time_utc"].asString()).toInstant(ZoneOffset.UTC),
                n["opening_price"].decimalValue(), n["high_price"].decimalValue(), n["low_price"].decimalValue(),
                n["trade_price"].decimalValue(), n["candle_acc_trade_volume"].decimalValue(), n["candle_acc_trade_price"].decimalValue(),
            )
        }
    }

    override fun tickers(): List<TickerSnapshot> =
        get("ticker/all?quote_currencies=KRW", Priority.RECONCILE).elements().map { n ->
            val instrument = id(n["market"].asString())
            val dayStart = LocalDate.parse(n["trade_date"].asString(), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant()
            TickerSnapshot(
                instrument, n["trade_price"].decimalValue(), n["signed_change_rate"].decimalValue(), n["acc_trade_price_24h"].decimalValue(),
                Candle(
                    instrument, CandleUnit.DAY, dayStart,
                    n["opening_price"].decimalValue(), n["high_price"].decimalValue(), n["low_price"].decimalValue(),
                    n["trade_price"].decimalValue(), n["acc_trade_volume"].decimalValue(), n["acc_trade_price"].decimalValue(),
                ),
            )
        }

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
                response.statusCode() in 200..299 -> return mapper.readTree(response.body())
                response.statusCode() == 429 && attempt < maxRetries -> {
                    Thread.sleep(retryBase.multipliedBy(1L shl attempt))
                    attempt++
                }
                else -> throw UpbitApiException(response.statusCode(), response.body())
            }
        }
    }

    private fun JsonNode.elements(): List<JsonNode> = (0 until size()).map { get(it) }

    private fun id(market: String) = InstrumentId.of(SOURCE, market)

    private fun encode(v: String) = URLEncoder.encode(v, Charsets.UTF_8)

    companion object {
        const val SOURCE = "UPBIT"
        private val TO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)
    }
}
