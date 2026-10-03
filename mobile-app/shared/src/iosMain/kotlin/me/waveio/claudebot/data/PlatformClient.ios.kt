package me.waveio.claudebot.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun platformClient(): HttpClient = HttpClient(Darwin) {
    followRedirects = false
    expectSuccess = false
}.ownedByBotApi()
