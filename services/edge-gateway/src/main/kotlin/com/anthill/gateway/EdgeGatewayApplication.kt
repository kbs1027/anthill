package com.anthill.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EdgeGatewayApplication

fun main(args: Array<String>) {
    runApplication<EdgeGatewayApplication>(*args)
}
