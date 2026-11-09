package com.anthill.marketdata

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class MarketdataApplicationTests(@Autowired private val jdbc: JdbcClient) {

    @Test
    fun `context loads and migration creates all tables`() {
        val tables = jdbc.sql(
            "select count(*) from information_schema.tables " +
                "where table_name in ('instrument','candle','coverage','sync_correction','outage')",
        ).query(Long::class.java).single()
        assertEquals(5L, tables)
    }
}
