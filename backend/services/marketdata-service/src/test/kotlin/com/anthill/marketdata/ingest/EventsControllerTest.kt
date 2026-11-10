package com.anthill.marketdata.ingest

import com.anthill.contract.Candle
import com.anthill.contract.CandleMerge
import com.anthill.contract.CandleUnit
import com.anthill.contract.ContractJson
import com.anthill.contract.Decimals
import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentId
import com.anthill.contract.SecondCandle
import com.anthill.contract.SecondCandleEvent
import com.anthill.contract.TradeEvent
import com.anthill.eventbus.EventEnvelope
import com.anthill.marketdata.PgTest
import com.anthill.marketdata.live.LiveCandleBook
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@AutoConfigureMockMvc
class EventsControllerTest : PgTest() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var book: LiveCandleBook

    @Test
    fun `duplicate trade batch is applied once`() {
        val id = InstrumentId.parse("UPBIT:KRW-DUP")
        val mapper = ContractJson.mapper()
        // 진행 중 캔들은 지금 시계로 끝난 칸이면 받지 않는다. 고정 날짜를 쓰면 날짜가 지나 실패하므로 지금 시각을 쓴다.
        val trade = TradeEvent(id, BigDecimal("100"), BigDecimal("0.5"), Instant.now(), 1)
        val envelope = EventEnvelope(UUID.randomUUID(), EventTypes.MARKET_TRADE, 1, id.value, Instant.now(), "upbit-adapter", mapper.valueToTree(trade))
        val body = mapper.writeValueAsString(listOf(envelope))
        repeat(2) {
            mvc.post("/internal/events") { contentType = MediaType.APPLICATION_JSON; content = body }.andExpect { status { isAccepted() } }
        }
        assertTrue(Decimals.same(BigDecimal("0.5"), book.current(id, CandleUnit.M1)!!.volume))
    }

    @Test
    fun `second candle event becomes the live minute candles`() {
        val id = InstrumentId.parse("BINANCE:EVTUSDT")
        val mapper = ContractJson.mapper()
        val start = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val second = SecondCandle(id, start, BigDecimal("2.5"), BigDecimal("2.6"), BigDecimal("2.4"), BigDecimal("2.55"), BigDecimal("10"), BigDecimal("25.3"))
        val envelope = EventEnvelope(
            UUID.randomUUID(), EventTypes.MARKET_SECOND_CANDLE, 1, id.value, Instant.now(), "binance-adapter", mapper.valueToTree(SecondCandleEvent(second)),
        )
        mvc.post("/internal/events") { contentType = MediaType.APPLICATION_JSON; content = mapper.writeValueAsString(listOf(envelope)) }
            .andExpect { status { isAccepted() } }
        for (u in LiveCandleBook.MINUTE_UNITS) {
            val c = book.current(id, u)!!
            assertTrue(Decimals.same(BigDecimal("10"), c.volume) && Decimals.same(BigDecimal("25.3"), c.amount) && Decimals.same(BigDecimal("2.55"), c.close), "$u $c")
        }
    }

    @Test
    fun `first second candle rebuilds the live candles from source minutes and seconds`() {
        val id = InstrumentId.parse("BINANCE:RBDUSDT")
        val mapper = ContractJson.mapper()
        // 진행 중 칸이 끝나지 않게 지금 시각으로 잡는다. 이벤트로 넣을 두 초(cut, cut+1)는 같은 분 안에 두고(분 시작 초가 아니어야 1초 캔들을 받는다),
        // cut이 4시간 칸의 첫 분이면 받을 1분봉이 없으므로 다음 분까지 기다린다(드묾).
        var cut: Instant
        while (true) {
            cut = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(2)
            while (cut.atOffset(ZoneOffset.UTC).second !in 1..58) cut = cut.minusSeconds(1)
            if (CandleUnit.M240.floor(cut) < CandleUnit.M1.floor(cut)) break
            Thread.sleep(200)
        }
        val minuteStart = CandleUnit.M1.floor(cut)
        val fourHourStart = CandleUnit.M240.floor(cut)

        fun d(v: Any) = BigDecimal(v.toString())
        // 소스 1분봉: 4시간 칸 시작부터 cut이 속한 분 직전까지 전부(최대 239개, 200개씩 나눠 받는다). 가끔 거래 없는 분을 섞는다.
        // 범위 밖(4시간 칸 앞 분, cut이 속한 분)에도 값을 넣어 두어 잘못 받으면 드러나게 한다.
        val inRangeMinutes = generateSequence(fourHourStart) { it.plusSeconds(60) }.takeWhile { it < minuteStart }.mapIndexed { k, t ->
            if (k % 5 == 3) {
                val p = d(200 + (k - 1) % 9)
                Candle(id, CandleUnit.M1, t, p, p, p, p, BigDecimal.ZERO, BigDecimal.ZERO)
            } else {
                Candle(id, CandleUnit.M1, t, d(200 + k % 9), d(210 + k % 9), d(190 + k % 9), d(200 + (k + 1) % 9), d(1 + k % 3), d((1 + k % 3) * 200))
            }
        }.toList()
        val outside = listOf(fourHourStart.minusSeconds(60), minuteStart).map {
            Candle(id, CandleUnit.M1, it, d(1), d(9999), d(1), d(1), d(777), d(777))
        }
        source.put(inRangeMinutes + outside)
        // 소스 1초 캔들: 분 시작부터 cut 직전까지. cut 초에도 다른 값을 넣어 둔다(이벤트로 받은 초가 이겨야 한다).
        val inRangeSeconds = generateSequence(minuteStart) { it.plusSeconds(1) }.takeWhile { it < cut }.mapIndexed { k, t ->
            if (k % 4 == 1) SecondCandle(id, t, d(205), d(205), d(205), d(205), BigDecimal.ZERO, BigDecimal.ZERO)
            else SecondCandle(id, t, d(205 + k % 3), d(207 + k % 3), d(203 + k % 3), d(206 + k % 3), d("0.5"), d("103"))
        }.toList()
        source.putSeconds(inRangeSeconds + SecondCandle(id, cut, d(1), d(9999), d(1), d(1), d(777), d(777)))

        // 이벤트로 넣는 초: 처음 받은 초(cut) = 끊김, 이어서 다음 초.
        val events = listOf(
            SecondCandle(id, cut, d("208.5"), d(230), d("180.25"), d(220), d(2), d(440)),
            SecondCandle(id, cut.plusSeconds(1), d(220), d(221), d(219), d("219.5"), d(3), d(660)),
        )
        val envelopes = events.map {
            EventEnvelope(UUID.randomUUID(), EventTypes.MARKET_SECOND_CANDLE, 1, id.value, Instant.now(), "binance-adapter", mapper.valueToTree(SecondCandleEvent(it)))
        }
        mvc.post("/internal/events") { contentType = MediaType.APPLICATION_JSON; content = mapper.writeValueAsString(envelopes) }
            .andExpect { status { isAccepted() } }

        // 기대값: 소스 1분봉 + 소스 1초 캔들 + 이벤트 초를 시간 순으로 CandleMerge로 합친 값.
        fun expected(u: CandleUnit): Candle {
            val bucket = u.floor(cut)
            val pieces = inRangeMinutes.filter { it.start >= bucket }.map { it.copy(unit = u, start = bucket) } +
                (inRangeSeconds + events).filter { it.start >= bucket }.map { it.toCandle(u, bucket) }
            return pieces.drop(1).fold(pieces.first()) { acc, p -> CandleMerge.merge(acc, p) }
        }
        val units = listOf(CandleUnit.M1, CandleUnit.M240) + LiveCandleBook.MINUTE_UNITS
        val until = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (units.any { book.current(id, it)?.sameValuesAs(expected(it)) != true } && System.nanoTime() < until) Thread.sleep(5)

        assertEquals(listOf(Triple(id, minuteStart, cut)), source.secondCalls.filter { it.first == id })
        assertTrue(source.callCount(id, CandleUnit.M1) >= 1)
        for (u in units.distinct()) {
            val c = book.current(id, u)
            assertTrue(c != null && c.sameValuesAs(expected(u)), "$u expected ${expected(u)} but was $c")
        }
    }
}
