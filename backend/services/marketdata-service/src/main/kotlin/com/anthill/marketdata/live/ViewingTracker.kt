package com.anthill.marketdata.live

import com.anthill.contract.InstrumentId
import com.anthill.contract.ViewingEvent
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** gateway들이 알려준 "지금 보고 있는 종목". 30초 넘게 소식이 없는 gateway의 보고는 버린다. */
class ViewingTracker(private val clock: Clock, private val ttl: Duration = Duration.ofSeconds(30)) {
    private val reports = ConcurrentHashMap<String, ViewingEvent>()

    fun update(e: ViewingEvent) { reports[e.gatewayId] = e }

    fun viewed(): Set<InstrumentId> {
        val cutoff = clock.instant().minus(ttl)
        return reports.values.filter { it.at.isAfter(cutoff) }.flatMap { it.instruments }.toSet()
    }
}
