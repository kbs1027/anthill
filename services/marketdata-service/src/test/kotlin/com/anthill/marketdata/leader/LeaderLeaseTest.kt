package com.anthill.marketdata.leader

import com.anthill.marketdata.PgTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeaderLeaseTest : PgTest() {

    @Autowired lateinit var client: JdbcClient

    private val ttl = Duration.ofMillis(400)

    @Test
    fun `only one holder wins and it can renew`() {
        val lease = LeaderLease(client, UUID.randomUUID().toString())
        assertTrue(lease.tryAcquire("a", ttl))
        assertFalse(lease.tryAcquire("b", ttl))
        assertTrue(lease.tryAcquire("a", ttl))
    }

    @Test
    fun `an expired lease goes to the other holder`() {
        val lease = LeaderLease(client, UUID.randomUUID().toString())
        assertTrue(lease.tryAcquire("a", ttl))
        Thread.sleep(ttl.toMillis() + 100)
        assertTrue(lease.tryAcquire("b", ttl))
        assertFalse(lease.tryAcquire("a", ttl))
    }
}
