package com.anthill.marketdata.config

import com.anthill.contract.ContractJson
import com.anthill.marketdata.candle.CandleChangeSink
import com.anthill.marketdata.candle.LiveView
import com.anthill.marketdata.source.HttpSourceClient
import com.anthill.marketdata.source.SourceClient
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.JacksonModule
import java.net.URI
import java.time.Clock

@ConfigurationProperties("anthill")
data class SourcesProperties(val sources: Map<String, URI> = emptyMap())

@Configuration
@EnableConfigurationProperties(SourcesProperties::class)
class MarketdataConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun contractJsonModule(): JacksonModule = ContractJson.contractModule()

    @Bean
    fun sourceClient(props: SourcesProperties): SourceClient = HttpSourceClient(props.sources, ContractJson.mapper())

    // 작업 7에서 실시간 쪽 구현으로 바꾼다.
    @Bean
    fun noLiveView(): LiveView = object : LiveView {
        override fun current(i: com.anthill.contract.InstrumentId, u: com.anthill.contract.CandleUnit) = null
        override fun streamConnectedSince(source: String) = null
    }

    @Bean
    fun noopChangeSink(): CandleChangeSink = CandleChangeSink { _, _, _, _ -> }
}
