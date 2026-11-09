package com.anthill.gateway.realtime

import com.anthill.contract.CandleUnit
import com.anthill.contract.InstrumentId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class SubscriptionRegistryTest {

    @Test
    fun `subscriptions per session are capped`() {
        val registry = SubscriptionRegistry(maxPerSession = 2)
        val a = InstrumentId.parse("UPBIT:KRW-A")
        registry.subscribe("s1", a, CandleUnit.M1)
        registry.subscribe("s1", a, CandleUnit.M5)
        registry.subscribe("s1", a, CandleUnit.M5)          // 같은 구독 반복은 한도에 안 센다
        assertThrows<IllegalArgumentException> { registry.subscribe("s1", a, CandleUnit.M15) }
        registry.subscribe("s2", a, CandleUnit.M15)          // 다른 세션은 따로 센다
        registry.unsubscribe("s1", a, CandleUnit.M1)
        registry.subscribe("s1", a, CandleUnit.M15)
        assertEquals(setOf("s1", "s2"), registry.sessionsFor(a, CandleUnit.M15))
    }
}
