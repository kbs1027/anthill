package com.anthill.gateway.realtime

import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId

/**
 * 세션별 구독. 캔들은 (종목, 단위) 단위로, 티커는 전체 목록 하나로 구독한다.
 * 한 세션의 캔들 구독은 [maxPerSession]개까지(공개 서비스라 한 연결이 허브를 독차지하지 못하게).
 */
class SubscriptionRegistry(private val maxPerSession: Int = 50) {
    private val lock = Any()
    private val byKey = HashMap<Pair<InstrumentId, CandleUnit>, MutableSet<String>>()
    private val bySession = HashMap<String, MutableSet<Pair<InstrumentId, CandleUnit>>>()
    private val tickers = HashSet<String>()

    /** @throws IllegalArgumentException 세션의 구독 수가 한도를 넘을 때 */
    fun subscribe(session: String, i: InstrumentId, u: CandleUnit) = synchronized(lock) {
        val mine = bySession.getOrPut(session) { HashSet() }
        val key = i to u
        if (key in mine) return
        require(mine.size < maxPerSession) { "too many subscriptions (max $maxPerSession)" }
        mine += key
        byKey.getOrPut(key) { HashSet() } += session
    }

    fun unsubscribe(session: String, i: InstrumentId, u: CandleUnit) = synchronized(lock) {
        val key = i to u
        bySession[session]?.remove(key)
        byKey[key]?.let { it.remove(session); if (it.isEmpty()) byKey.remove(key) }
    }

    fun subscribeTickers(session: String) = synchronized(lock) { tickers += session }

    fun remove(session: String) = synchronized(lock) {
        bySession.remove(session)?.forEach { key -> byKey[key]?.let { it.remove(session); if (it.isEmpty()) byKey.remove(key) } }
        tickers -= session
    }

    fun sessionsFor(i: InstrumentId, u: CandleUnit): Set<String> = synchronized(lock) { byKey[i to u]?.toSet() ?: emptySet() }

    fun tickerSessions(): Set<String> = synchronized(lock) { tickers.toSet() }

    /** 지금 차트로 보고 있는 종목(티커 목록만 보는 것은 빼고). */
    fun viewed(): Set<InstrumentId> = synchronized(lock) { byKey.keys.mapTo(HashSet()) { it.first } }
}
