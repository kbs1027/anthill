package com.anthill.marketdata.leader

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeadershipTest {

    private var acquired = true
    private var failing = false
    private var nanos = 0L
    private val store = LeaseStore { _, _ ->
        if (failing) throw IllegalStateException("db down")
        acquired
    }
    private val leadership = Leadership(store, "me", Duration.ofSeconds(1)) { nanos }

    @Test
    fun `winning the lease promotes once`() {
        var promoted = 0
        leadership.onPromoted { promoted++ }
        assertFalse(leadership.isLeader())
        leadership.tick()
        leadership.tick()
        assertTrue(leadership.isLeader())
        assertEquals(1, promoted)
    }

    @Test
    fun `losing the lease steps down`() {
        leadership.tick()
        acquired = false
        leadership.tick()
        assertFalse(leadership.isLeader())
    }

    @Test
    fun `a leader that could not renew in time stops publishing`() {
        leadership.tick()
        nanos += Duration.ofMillis(1100).toNanos()
        assertFalse(leadership.isLeader())
    }

    @Test
    fun `a database outage keeps the current leader so the chart keeps moving`() {
        leadership.tick()
        failing = true
        leadership.tick()
        nanos += Duration.ofSeconds(10).toNanos()
        assertTrue(leadership.isLeader())
    }

    @Test
    fun `a database outage does not promote a standby`() {
        failing = true
        leadership.tick()
        assertFalse(leadership.isLeader())
    }

    @Test
    fun `always is a leader without ticking`() {
        assertTrue(Leadership.always().isLeader())
    }
}
