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
    fun `only audited syncs push changes to viewers`() {
        // spec 6절: 마감 캔들을 다시 받아 저장값과 다를 때만 정정이고, 그때만 화면에 다시 보낸다.
        // 과거 채우기·과거 조회로 처음 저장하는 캔들까지 보내면 시청자에게 수만 건이 쏟아진다(Task 12 실측 1분에 16,181건).
        val pushed = ArrayList<Pair<List<Instant>, List<Instant>>>()
        val s = CandleSync(source, candles, coverage, CandleChangeSink { _, _, up, del -> pushed += up.map { it.start } to del }, correctionLog)
        source.put(FakeSource.series(t0, 5))
        s.syncBefore(btc, CandleUnit.M1, t(5), Priority.USER)
        s.syncWindow(btc, CandleUnit.M1, t(0), t(5), Priority.BACKFILL, audit = false)
        assertEquals(emptyList(), pushed)

        candles.upsert(listOf(FakeSource.candle(t(1), close = "999")))
        s.syncWindow(btc, CandleUnit.M1, t(0), t(5), Priority.RECONCILE, audit = true)
        assertEquals(listOf(listOf(t(1)) to emptyList<Instant>()), pushed)
    }
}
