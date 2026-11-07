package com.anthill.gateway.realtime

import com.anthill.contract.CandleUnit
import com.anthill.contract.EventTypes
import com.anthill.contract.InstrumentId
import com.anthill.contract.ViewingEvent
import com.anthill.eventbus.EventPublisher
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class ViewingPublisherTest {

    private val btc = InstrumentId.parse("UPBIT:KRW-BTC")
    private val eth = InstrumentId.parse("UPBIT:KRW-ETH")
    private val now = Instant.parse("2026-11-08T10:20:30Z")

    @Test
    fun `viewing publisher sends current viewed set`() {
        val sent = ArrayList<Triple<String, String, Any>>()
        val registry = SubscriptionRegistry()
        val viewing = ViewingPublisher(registry, EventPublisher { t, k, p -> sent += Triple(t, k, p) }, "gw-1", Clock.fixed(now, ZoneOffset.UTC))
        registry.subscribe("s1", btc, CandleUnit.M1)
        registry.subscribe("s1", btc, CandleUnit.M5)
        registry.subscribe("s2", eth, CandleUnit.DAY)
        registry.subscribeTickers("s3")

        viewing.publish()
        assertEquals(Triple(EventTypes.REALTIME_VIEWING, "gw-1", ViewingEvent("gw-1", setOf(btc, eth), now) as Any), sent.single())

        registry.remove("s1")
        viewing.publish()
        assertEquals(ViewingEvent("gw-1", setOf(eth), now), sent.last().third)

        registry.remove("s2")
        viewing.publish()
        assertEquals(ViewingEvent("gw-1", emptySet(), now), sent.last().third)
    }
}
