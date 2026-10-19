@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Real controller SSE updates drive a silent recording actuator. */
class ControllerAgentHapticsTest {
    @Test fun liveEventsDrivePatternsAndReplayBackgroundAndCompletionStayQuiet() = runTest {
        val bridge = FakePlatformBridge().apply { supportsAgentHaptics = true; clock = { testScheduler.currentTime } }
        val fixture = ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge)
        val channel = ByteChannel(autoFlush = true)
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" -> jsonResponse("""{"messages":[{"id":"job","session_id":"chat_1","state":"running"}]}""")
            request.url.encodedPath == "/api/mobile/messages/job/events" -> respond(channel, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            else -> null
        } }
        suspend fun event(id: Int, kind: String, data: String) {
            channel.writeStringUtf8("id: $id\nevent: $kind\ndata: $data\n\n")
            runCurrent()
        }
        try {
            runCurrent()
            fixture.controller.openChat("chat_1"); runCurrent()
            advanceTimeBy(600); runCurrent()
            assertEquals(AgentHaptic.Thinking, bridge.agentPulses.last().first)
            event(1, "tool_start", """{"step":{"id":"call","label":"Search","status":"running"}}""")
            advanceTimeBy(180); runCurrent()
            assertEquals(AgentHaptic.Tool, bridge.agentPulses.last().first)
            event(2, "tool_done", """{"step":{"id":"call","status":"done"}}""")
            advanceTimeBy(300); runCurrent()
            event(3, "delta", """{"chunk":"Hello"}""")
            assertEquals(AgentHaptic.Streaming, bridge.agentPulses.last().first)
            val count = bridge.agentPulses.size
            event(3, "delta", """{"chunk":"Duplicated"}""")
            assertEquals(count, bridge.agentPulses.size)
            bridge.foreground.value = false; runCurrent()
            advanceTimeBy(600); event(4, "delta", """{"chunk":" in background"}""")
            assertEquals(count, bridge.agentPulses.size)
            // A terminal row suppresses feedback even before mobile_state arrives.
            event(5, "done", """{"reply":"Finished"}""")
            bridge.foreground.value = true; runCurrent()
            advanceTimeBy(600); runCurrent()
            assertEquals(count, bridge.agentPulses.size)
        } finally { fixture.close(); channel.close(); runCurrent() }
    }

    @Test fun settingsPersistAndPreviewsOnlyRunOnTheirSettingsScreen() = runTest {
        val bridge = FakePlatformBridge().apply { supportsAgentHaptics = true }
        val fixture = ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge)
        try {
            runCurrent()
            fixture.controller.preferences(Preferences(thinkingHaptics = false, hapticStrength = "strong", hapticCadence = "calm"))
            val saved = Json.decodeFromString<Preferences>(bridge.preferences.getValue("preferences.v1"))
            assertFalse(saved.thinkingHaptics)
            assertEquals("strong", saved.hapticStrength)
            assertEquals("calm", saved.hapticCadence)
            fixture.controller.previewHaptic(AgentHaptic.Tool)
            assertTrue(bridge.agentPulses.isEmpty())
            fixture.controller.navigate(Screen.Haptics); runCurrent()
            fixture.controller.previewHaptic(AgentHaptic.Tool)
            assertEquals(listOf(AgentHaptic.Tool), bridge.agentPulses.map { it.first })
        } finally { fixture.close(); runCurrent() }
    }
}
