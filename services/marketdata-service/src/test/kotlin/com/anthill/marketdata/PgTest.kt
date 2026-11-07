package com.anthill.marketdata

import com.anthill.marketdata.source.SourceClient
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient

@TestConfiguration(proxyBeanMethods = false)
class TestSourceConfig {
    @Bean
    @Primary
    fun fakeSource(): FakeSource = FakeSource()
}

/** PostgreSQL 18 컨테이너와 가짜 소스를 쓰는 테스트의 공통 바탕. 테스트마다 테이블을 비운다. */
@SpringBootTest
@Import(TestcontainersConfiguration::class, TestSourceConfig::class)
abstract class PgTest {
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var source: FakeSource

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("truncate instrument, candle, coverage, sync_correction, outage restart identity").update()
        source.reset()
    }

    @Suppress("unused")
    private fun keepSourceClientTypeReferenced(s: SourceClient) = s
}
