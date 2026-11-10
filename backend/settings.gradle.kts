pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "anthill"

include(
    "libs:market-contract",
    "libs:event-bus",
    "services:upbit-adapter",
    "services:binance-adapter",
    "services:marketdata-service",
    "services:edge-gateway",
)
