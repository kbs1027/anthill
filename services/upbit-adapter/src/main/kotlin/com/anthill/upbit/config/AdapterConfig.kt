package com.anthill.upbit.config

import com.anthill.contract.ContractJson
import com.anthill.upbit.rest.HttpUpbitRestClient
import com.anthill.upbit.rest.RequestBudget
import com.anthill.upbit.rest.UpbitRestClient
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.JacksonModule

@ConfigurationProperties("anthill.upbit")
data class UpbitProperties(val restBaseUrl: String, val wsUrl: String, val restRatePerSecond: Int = 9)

@Configuration
@EnableConfigurationProperties(UpbitProperties::class)
class AdapterConfig {

    @Bean
    fun contractJsonModule(): JacksonModule = ContractJson.contractModule()

    @Bean
    fun requestBudget(props: UpbitProperties) = RequestBudget(props.restRatePerSecond)

    @Bean
    fun upbitRestClient(props: UpbitProperties, budget: RequestBudget): UpbitRestClient =
        HttpUpbitRestClient(props.restBaseUrl, budget)
}
