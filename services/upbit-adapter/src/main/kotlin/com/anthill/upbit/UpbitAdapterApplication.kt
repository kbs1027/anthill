package com.anthill.upbit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class UpbitAdapterApplication

fun main(args: Array<String>) {
    runApplication<UpbitAdapterApplication>(*args)
}
