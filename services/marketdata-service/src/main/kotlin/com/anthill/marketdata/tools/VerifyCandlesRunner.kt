package com.anthill.marketdata.tools

import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import com.anthill.contract.Priority
import com.anthill.marketdata.candle.CandleRepository
import com.anthill.marketdata.candle.CoverageRepository
import com.anthill.marketdata.config.SourcesProperties
import com.anthill.marketdata.source.SourceClient
import com.anthill.marketdata.sync.OutageLog
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import kotlin.system.exitProcess

data class VerifyReport(
    val checkedWindows: Int,
    val checkedCandles: Int,
    val mismatches: List<String>,
    val outages: List<Pair<Instant, Instant>>,
)

/** 우리 DB의 마감 캔들을 소스 REST와 직접 대조한다. 검사 시한이 지난 캔들만 본다. */
class CandleVerifier(
    private val source: SourceClient,
    private val candles: CandleRepository,
    private val coverage: CoverageRepository,
    private val outages: OutageLog,
) {
    fun verify(samples: List<Pair<InstrumentId, CandleUnit>>, now: Instant): VerifyReport {
        var windows = 0
        var checked = 0
        val mismatches = ArrayList<String>()
        for ((i, u) in samples) {
            val end = coverage.end(i, u) ?: continue
            val to = minOf(end, u.floor(now.minus(u.reconcileDeadline)))
            val coveredFrom = coverage.coveredStartFor(i, u, to) ?: continue
            val fromSource = source.candles(i, u, to, 200, Priority.BACKFILL)
            val from = maxOf(coveredFrom, if (fromSource.size == 200) fromSource.last().start else Instant.EPOCH)
            val expected = fromSource.filter { it.start >= from }.sortedBy { it.start }
            val actual = candles.findRange(i, u, from, to)
            windows++
            checked += expected.size
            if (expected.size != actual.size) mismatches += "$i ${u.code} [$from, $to): source ${expected.size} candles, db ${actual.size}"
            val byStart = actual.associateBy { it.start }
            for (e in expected) {
                val a = byStart[e.start] ?: continue
                if (!a.sameValuesAs(e)) mismatches += "$i ${u.code} ${e.start}: source $e, db $a"
            }
        }
        return VerifyReport(windows, checked, mismatches, outages.windows(Instant.EPOCH, now))
    }
}

/** verify 프로필: 거래대금 상위 5·하위 5·무작위 5 종목 × 11개 단위를 대조하고, 불일치가 있으면 종료 코드 1. */
@Component
@Profile("verify")
class VerifyCandlesRunner(
    private val source: SourceClient,
    private val candles: CandleRepository,
    private val coverage: CoverageRepository,
    private val outages: OutageLog,
    private val sources: SourcesProperties,
    private val clock: Clock,
    private val context: ApplicationContext,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val tickers = sources.sources.keys.flatMap { source.tickers(it) }.sortedByDescending { it.accTradePrice24h }
        val ids = (tickers.take(5) + tickers.takeLast(5) + tickers.shuffled().take(5)).map { it.instrument }.distinct()
        val report = CandleVerifier(source, candles, coverage, outages).verify(ids.flatMap { i -> CandleUnit.entries.map { i to it } }, clock.instant())
        println("checked windows=${report.checkedWindows} candles=${report.checkedCandles} mismatches=${report.mismatches.size}")
        report.mismatches.forEach { println("MISMATCH $it") }
        report.outages.forEach { (f, t) -> println("OUTAGE $f .. $t") }
        exitProcess(SpringApplication.exit(context, { if (report.mismatches.isEmpty()) 0 else 1 }))
    }
}
