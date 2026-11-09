package com.anthill.marketdata.candle

import com.anthill.contract.CandleUnit
import com.anthill.contract.Priority
import com.anthill.marketdata.FakeSource
import com.anthill.marketdata.PgTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandleSyncTest : PgTest() {

    @Autowired lateinit var sync: CandleSync
    @Autowired lateinit var candles: CandleRepository
    @Autowired lateinit var coverage: CoverageRepository
    @Autowired lateinit var correctionLog: CorrectionLog

    private val btc = FakeSource.BTC
    private val t0 = Instant.parse("2026-11-05T00:00:00Z")
    private fun t(min: Long) = t0.plusSeconds(min * 60)
    private fun corrections() = jdbc.sql("select count(*) from sync_correction").query(Long::class.java).single()

    @Test
    fun `syncWindow makes db equal to source`() {
        source.put(FakeSource.series(t0, 5))
        source.remove(btc, CandleUnit.M1, t(3))                              // 소스: t0 t1 t2 t4
        candles.upsert(listOf(FakeSource.candle(t(1), close = "999")))       // DB 값이 틀림
        candles.upsert(listOf(FakeSource.candle(t(9), close = "100")))       // DB에만 있음
        val result = sync.syncWindow(btc, CandleUnit.M1, t(0), t(10), Priority.RECONCILE, audit = true)
        val db = candles.findRange(btc, CandleUnit.M1, t(0), t(10))
        assertEquals(listOf(t(0), t(1), t(2), t(4)), db.map { it.start })
        assertTrue(db.zip(source.candles(btc, CandleUnit.M1, t(10), 200, Priority.USER).reversed()).all { (a, b) -> a.sameValuesAs(b) })
        assertEquals(listOf(t(9)), result.deleted)
        assertEquals(t(0), coverage.coveredStartFor(btc, CandleUnit.M1, t(10)))
        assertEquals(5L, corrections()) // 추가 t0·t2·t4, 갱신 t1, 삭제 t9
    }

    @Test
    fun `syncWindow pages when window holds over 200`() {
        source.put(FakeSource.series(t0, 500))
        sync.syncWindow(btc, CandleUnit.M1, t(0), t(500), Priority.BACKFILL, audit = false)
        assertEquals(3, source.callCount(btc, CandleUnit.M1))
        assertEquals(500, candles.findRange(btc, CandleUnit.M1, t(0), t(500)).size)
    }

    @Test
    fun `equal values with different scale are not corrections`() {
        val c = FakeSource.candle(t0, close = "108328000")
        candles.upsert(listOf(c))
        source.put(listOf(c.copy(close = BigDecimal("108328000.00000000"), amount = c.amount.setScale(10))))
        val result = sync.syncWindow(btc, CandleUnit.M1, t(0), t(1), Priority.RECONCILE, audit = true)
        assertTrue(result.upserted.isEmpty())
        assertEquals(0L, corrections())
    }

    @Test
    fun `empty window still extends coverage`() {
        sync.syncWindow(btc, CandleUnit.M1, t(0), t(30), Priority.RECONCILE, audit = true)
        assertEquals(t(0), coverage.coveredStartFor(btc, CandleUnit.M1, t(30)))
        assertTrue(candles.findRange(btc, CandleUnit.M1, t(0), t(30)).isEmpty())
    }

    @Test
    fun `syncBefore at history start covers from EPOCH`() {
        source.put(FakeSource.series(t0, 150))
        val result = sync.syncBefore(btc, CandleUnit.M1, t(150), Priority.USER)
        assertTrue(result.reachedHistoryStart)
        assertEquals(Instant.EPOCH, coverage.coveredStartFor(btc, CandleUnit.M1, t(150)))
        assertEquals(150, candles.findRange(btc, CandleUnit.M1, Instant.EPOCH, t(150)).size)
    }

    @Test
    fun `syncBefore with full page covers from oldest returned`() {
        source.put(FakeSource.series(t0, 250))
        val result = sync.syncBefore(btc, CandleUnit.M1, t(250), Priority.USER)
        assertEquals(false, result.reachedHistoryStart)
        assertEquals(t(50), coverage.coveredStartFor(btc, CandleUnit.M1, t(250)))
    }

    @Test
    fun `audit false does not log corrections`() {
        source.put(FakeSource.series(t0, 5))
        sync.syncWindow(btc, CandleUnit.M1, t(0), t(5), Priority.BACKFILL, audit = false)
        assertEquals(0L, corrections())
    }

    @Test
    fun `backfill and old history loads are not pushed to viewers`() {
        // spec 6절: 정정만 화면에 다시 보낸다. 과거 채우기·과거 조회로 처음 저장하는 캔들까지 보내면 시청자에게 수만 건이 쏟아진다(Task 12 실측 1분에 16,181건).
        val pushed = ArrayList<Pair<List<Instant>, List<Instant>>>()
        val s = syncWith { up, del -> pushed += up.map { it.start } to del }
        source.put(FakeSource.series(t0, 5))
        s.syncBefore(btc, CandleUnit.M1, t(5), Priority.USER)
        s.syncWindow(btc, CandleUnit.M1, t(0), t(5), Priority.BACKFILL, audit = false)
        assertEquals(emptyList(), pushed)

        candles.upsert(listOf(FakeSource.candle(t(1), close = "999")))
        s.syncWindow(btc, CandleUnit.M1, t(0), t(5), Priority.RECONCILE, audit = true)
        assertEquals(listOf(listOf(t(1)) to emptyList<Instant>()), pushed)
    }

    @Test
    fun `history load after the coverage end pushes what viewers may have missed`() {
        // 리뷰 I3: 재시작 동안 열려 있던 차트에는 빈 분이 있다. 다른 사람의 과거 조회가 그 분을 먼저 채우면 복구는 차이를 못 본다.
        val pushed = ArrayList<Instant>()
        val s = syncWith { up, _ -> pushed += up.map { it.start } }
        coverage.add(btc, CandleUnit.M1, Instant.EPOCH, t(2))
        source.put(FakeSource.series(t0, 5))
        s.syncBefore(btc, CandleUnit.M1, t(5), Priority.USER)
        assertEquals(listOf(t(2), t(3), t(4)), pushed)
    }

    @Test
    fun `newest unsettled candle is stored but not marked verified`() {
        // 리뷰 I4: 방금 마감된 캔들은 업비트 쪽에서 아직 진행 중일 수 있다(시계 차이). 저장은 하되 확인 구간에는 넣지 않는다.
        source.put(FakeSource.series(t0, 5))
        candles.upsert(listOf(FakeSource.candle(t(4).plusSeconds(0), close = "1")))
        sync.syncWindow(btc, CandleUnit.M1, t(0), t(5), Priority.RECONCILE, audit = true, settledBefore = t(4))
        assertEquals(t(4), coverage.end(btc, CandleUnit.M1))
        assertEquals(listOf(t(0), t(1), t(2), t(3), t(4)), candles.findRange(btc, CandleUnit.M1, t(0), t(5)).map { it.start })
        assertTrue(candles.findRange(btc, CandleUnit.M1, t(4), t(5)).single().close.compareTo(BigDecimal("1")) != 0)
    }

    @Test
    fun `unsettled range never deletes`() {
        source.put(FakeSource.series(t0, 3))
        candles.upsert(listOf(FakeSource.candle(t(4))))   // 소스에 아직 안 보이는 방금 마감된 캔들
        sync.syncWindow(btc, CandleUnit.M1, t(0), t(5), Priority.RECONCILE, audit = true, settledBefore = t(4))
        assertEquals(listOf(t(0), t(1), t(2), t(4)), candles.findRange(btc, CandleUnit.M1, t(0), t(5)).map { it.start })
    }

    private fun syncWith(push: (List<com.anthill.contract.Candle>, List<Instant>) -> Unit) =
        CandleSync(source, candles, coverage, CandleChangeSink { _, _, up, del -> push(up, del) }, correctionLog)
}
