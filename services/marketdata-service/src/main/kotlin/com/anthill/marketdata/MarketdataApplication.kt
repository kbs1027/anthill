package com.anthill.marketdata

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class MarketdataApplication

fun main(args: Array<String>) {
    runApplication<MarketdataApplication>(*args)
}
