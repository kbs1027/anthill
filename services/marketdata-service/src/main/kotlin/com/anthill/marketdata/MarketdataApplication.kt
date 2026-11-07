package com.anthill.marketdata

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MarketdataApplication

fun main(args: Array<String>) {
    runApplication<MarketdataApplication>(*args)
}
