package me.waveio.claudebot.data

import io.ktor.client.HttpClient
import io.ktor.util.AttributeKey

expect fun platformClient(): HttpClient

// HttpClient.config() shares its engine but does not own it. Retain and close
// the original native client when BotApi created it through the default factory.
internal val PlatformClientOwner = AttributeKey<Unit>("BotApi.PlatformClientOwner")
internal fun HttpClient.ownedByBotApi(): HttpClient = apply { attributes.put(PlatformClientOwner, Unit) }
