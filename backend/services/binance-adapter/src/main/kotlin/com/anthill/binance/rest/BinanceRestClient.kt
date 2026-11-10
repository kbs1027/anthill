package com.anthill.binance.rest

import com.anthill.binance.rest.BinanceJson.elements
import com.anthill.contract.Candle
import com.anthill.contract.CandleMerge
import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentInfo
import com.anthill.contract.Priority
import com.anthill.contract.SecondCandle
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface BinanceRestClient {
    /** 현물 USDT 종목 중 거래 중인 것만, 심볼 순. 이름은 둘 다 baseAsset(예: `BTC`). */
    fun markets(): List<InstrumentInfo>

    /** 최신→과거 순. [to]는 배타적(그 시각에 시작하는 캔들은 빠짐), [count]는 1..200. 10분봉은 1분봉을 합쳐 만든다. */
    fun candles(symbol: String, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle>

    /** 1초 캔들, 과거→최신 순. [from] 포함, [to] 배타, 최대 [MAX_SECONDS]초. */
    fun seconds(symbol: String, from: Instant, to: Instant, priority: Priority): List<SecondCandle>

    /** UTC 당일 시세. 100종목씩 나눠 요청한다. */
    fun tradingDays(symbols: List<String>): List<TradingDay>

    /** 종목별 최근 24시간 거래대금(USDT). */
    fun amounts24h(): Map<String, BigDecimal>

    companion object {
        const val MAX_SECONDS = 1000L
    }
}

class BinanceApiException(val status: Int, message: String) : RuntimeException(message)

/**
 * 바이낸스 공개 시세 REST. 모든 요청은 [budget]의 분당 무게 예산을 지난다.
 * 429는 `Retry-After`만큼 모든 요청을 멈췄다가 [maxRetries]번까지 다시 하고, 418(IP 차단)은 멈추고 실패한다.
 */
class HttpBinanceRestClient(
    private val baseUrl: String,
    private val budget: WeightBudget,
    private val clock: Clock = Clock.systemUTC(),
    private val maxStreams: Int = 1000,
    private val http: HttpClient = HttpClient.newHttpClient(),
    private val maxRetries: Int = 3,
) : BinanceRestClient {

    private val log = Logger.getLogger(HttpBinanceRestClient::class.java.name)

    override fun markets(): List<InstrumentInfo> {
        val all = get("exchangeInfo?permissions=SPOT&symbolStatus=TRADING", Priority.RECONCILE, W_EXCHANGE_INFO)["symbols"].elements()
            .filter { it["quoteAsset"].asString() == "USDT" && it["status"].asString() == "TRADING" }
            .sortedBy { it["symbol"].asString() }
        if (all.size > maxStreams) log.warning("바이낸스 USDT 종목이 ${all.size}개라 앞의 ${maxStreams}개만 씁니다(웹소켓 하나의 구독 한도)")
        return all.take(maxStreams).map {
            val base = it["baseAsset"].asString()
            InstrumentInfo(BinanceJson.id(it["symbol"].asString()), base, base)
        }
    }

    override fun candles(symbol: String, unit: CandleUnit, to: Instant?, count: Int, priority: Priority): List<Candle> {
        require(count in 1..200) { "count must be 1..200: $count" }
        if (unit == CandleUnit.M10) return tenMinutes(symbol, to, count, priority)
        val query = buildString {
            append("symbol=").append(enc(symbol)).append("&interval=").append(BinanceJson.interval(unit)).append("&limit=").append(count)
            if (to != null) append("&endTime=").append(to.toEpochMilli() - 1)
        }
        return get("klines?$query", priority, W_KLINES).elements().map { BinanceJson.candle(it, symbol, unit) }.reversed()
    }

    /**
     * 바이낸스에는 10분봉이 없다. 원하는 칸들을 덮는 1분봉을 1000개씩 받아 [CandleMerge]로 10분씩 합친다.
     * 1분봉이 하나도 없는 칸(상장 전)은 뺀다.
     */
    private fun tenMinutes(symbol: String, to: Instant?, count: Int, priority: Priority): List<Candle> {
        val unit = CandleUnit.M10
        val last = unit.floor((to ?: clock.instant()).minusMillis(1))
        val first = last.minus(Duration.ofMinutes(10L * (count - 1)))
        val endMs = unit.next(last).toEpochMilli() - 1
        val minutes = mutableListOf<Candle>()
        var startMs = first.toEpochMilli()
        while (startMs <= endMs) {
            val pageEnd = minOf(endMs, startMs + PAGE_MINUTES * 60_000 - 1)
            val query = "symbol=${enc(symbol)}&interval=1m&startTime=$startMs&endTime=$pageEnd&limit=$PAGE_MINUTES"
            minutes += get("klines?$query", priority, W_KLINES).elements().map { BinanceJson.candle(it, symbol, CandleUnit.M1) }
            startMs = pageEnd + 1
        }
        return minutes
            .groupBy { unit.floor(it.start) }
            .map { (bucket, pieces) ->
                val sorted = pieces.sortedBy { it.start }
                sorted.drop(1).fold(sorted.first().copy(unit = unit, start = bucket)) { acc, piece -> CandleMerge.merge(acc, piece) }
            }
            .sortedByDescending { it.start }
    }

    override fun seconds(symbol: String, from: Instant, to: Instant, priority: Priority): List<SecondCandle> {
        val span = Duration.between(from, to)
        require(!span.isNegative && !span.isZero) { "to must be after from: $from..$to" }
        require(span <= Duration.ofSeconds(BinanceRestClient.MAX_SECONDS)) { "at most ${BinanceRestClient.MAX_SECONDS} seconds: $from..$to" }
        val query = "symbol=${enc(symbol)}&interval=1s&startTime=${from.toEpochMilli()}&endTime=${to.toEpochMilli() - 1}&limit=1000"
        return get("klines?$query", priority, W_KLINES).elements().map { BinanceJson.second(it, symbol) }
    }

    override fun tradingDays(symbols: List<String>): List<TradingDay> =
        symbols.chunked(TRADING_DAY_CHUNK).flatMap { chunk ->
            val list = chunk.joinToString(",", "[", "]") { "\"$it\"" }
            val weight = minOf(W_TRADING_DAY_PER_SYMBOL * chunk.size, W_TRADING_DAY_MAX)
            get("ticker/tradingDay?symbols=${enc(list)}", Priority.RECONCILE, weight).elements().map(BinanceJson::tradingDay)
        }

    override fun amounts24h(): Map<String, BigDecimal> =
        get("ticker/24hr?type=MINI", Priority.RECONCILE, W_TICKER_24H_ALL).elements()
            .associate { it["symbol"].asString() to BinanceJson.dec(it["quoteVolume"]) }

    private fun get(pathAndQuery: String, priority: Priority, weight: Int): JsonNode {
        val request = HttpRequest.newBuilder(URI("$baseUrl/api/v3/$pathAndQuery"))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        var attempt = 0
        while (true) {
            val response = budget.run(priority, weight) { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            val status = response.statusCode()
            when {
                status in 200..299 -> {
                    checkUsedWeight(response)
                    return BinanceJson.mapper.readTree(response.body())
                }
                status == 429 -> {
                    pause(response)
                    if (attempt >= maxRetries) throw BinanceApiException(status, "바이낸스 요청 한도 초과(429), 재시도 ${maxRetries}번 모두 실패: ${response.body()}")
                    attempt++
                }
                status == 418 -> {
                    pause(response)
                    throw BinanceApiException(status, "바이낸스가 이 IP를 잠시 막았다(418): ${response.body()}")
                }
                status == 451 -> throw BinanceApiException(status, "이 지역 IP는 바이낸스가 막는다(451). 미국 밖에서 실행해야 한다: ${response.body()}")
                else -> throw BinanceApiException(status, "바이낸스 응답 $status: ${response.body()}")
            }
        }
    }

    /** `Retry-After`(초)만큼 모든 요청을 멈춘다. 없으면 1초. */
    private fun pause(response: HttpResponse<*>) {
        val after = response.headers().firstValue("Retry-After").map { it.trim().toLongOrNull() }.orElse(null) ?: 1L
        log.warning("바이낸스가 ${response.statusCode()}을 돌려줘서 ${after}초 동안 요청을 멈춥니다")
        budget.pauseFor(after.seconds)
    }

    /** 바이낸스가 센 이번 분 무게가 한도의 90%를 넘으면(같은 IP의 다른 호출 포함) 다음 분까지 멈춘다. */
    private fun checkUsedWeight(response: HttpResponse<*>) {
        val used = response.headers().firstValue("X-MBX-USED-WEIGHT-1M").map { it.trim().toIntOrNull() }.orElse(null) ?: return
        if (used * 10 <= SERVER_WEIGHT_LIMIT * 9) return
        val untilNextMinute = 60_000 - clock.millis() % 60_000
        log.warning("바이낸스 무게가 이번 분에 ${used}/${SERVER_WEIGHT_LIMIT}라 다음 분까지 요청을 멈춥니다")
        budget.pauseFor(untilNextMinute.milliseconds)
    }

    private fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8)

    companion object {
        /** 바이낸스 IP당 분당 무게 한도(`exchangeInfo`의 REQUEST_WEIGHT). */
        const val SERVER_WEIGHT_LIMIT = 6000
        const val W_KLINES = 2
        const val W_EXCHANGE_INFO = 20
        const val W_TICKER_24H_ALL = 80
        const val W_TRADING_DAY_PER_SYMBOL = 4
        const val W_TRADING_DAY_MAX = 200
        const val TRADING_DAY_CHUNK = 100
        const val PAGE_MINUTES = 1000L
    }
}
