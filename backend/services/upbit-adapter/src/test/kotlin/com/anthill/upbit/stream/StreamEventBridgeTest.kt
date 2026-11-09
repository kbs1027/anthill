package com.anthill.upbit.stream

import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentId
import com.anthill.contract.StreamStatusEvent
import com.anthill.contract.TradeEvent
import com.anthill.eventbus.EventPublisher
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals

class StreamEventBridgeTest {

    private val published = mutableListOf<Triple<String, String, Any>>()
    private val bridge = StreamEventBridge(EventPublisher { type, key, payload -> published += Triple(type, key, payload) })

    @Test
    fun `bridge publishes typed events with keys`() {
        val trade = TradeEvent(InstrumentId.parse("UPBIT:KRW-BTC"), BigDecimal.ONE, BigDecimal.TEN, Instant.parse("2026-11-08T00:00:00Z"), 1)
        bridge.onMessage(UpbitTradeMsg(trade))
        bridge.onConnected(Instant.parse("2026-11-08T00:00:01Z"))
        bridge.onDisconnected(Instant.parse("2026-11-08T00:00:02Z"))
        assertEquals(Triple(EventTypes.MARKET_TRADE, "UPBIT:KRW-BTC", trade as Any), published[0])
        assertEquals(Triple(EventTypes.MARKET_STREAM_STATUS, "UPBIT", StreamStatusEvent("UPBIT", true, Instant.parse("2026-11-08T00:00:01Z")) as Any), published[1])
        assertEquals(StreamStatusEvent("UPBIT", false, Instant.parse("2026-11-08T00:00:02Z")), published[2].third)
    }
}
