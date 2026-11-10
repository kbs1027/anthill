package com.anthill.binance

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class BinanceAdapterApplication

fun main(args: Array<String>) {
    runApplication<BinanceAdapterApplication>(*args)
}
