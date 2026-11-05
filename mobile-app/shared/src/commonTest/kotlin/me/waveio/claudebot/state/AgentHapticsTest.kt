@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class AgentHapticsTest {
    private fun active(row: MessageRow? = null, preferences: Preferences = Preferences()) = AppState(
        connected = true, busy = true, sessionId = "chat", activeJobId = "job",
        messages = listOfNotNull(row), preferences = preferences,
    )
    private fun reply(text: String = "", steps: List<ActivityRow> = emptyList(), live: Boolean = true) =
        MessageRow("a-job", "assistant", text, steps = steps, live = live)

    private fun TestScope.fixture(): Pair<FakePlatformBridge, AgentHaptics> {
        val platform = FakePlatformBridge().apply { supportsAgentHaptics = true; clock = { testScheduler.currentTime } }
        return platform to AgentHaptics(backgroundScope, platform)
    }
    private fun TestScope.elapse(milliseconds: Long) { advanceTimeBy(milliseconds); runCurrent() }

    @Test fun thinkingToolsAndStreamingHaveDistinctPatternsAndTiming() = runTest {
        val (platform, haptics) = fixture()
        haptics.observe(active(), true)
        elapse(499); assertTrue(platform.agentPulses.isEmpty())
        elapse(1); assertEquals(listOf(AgentHaptic.Thinking), platform.agentPulses.map { it.first })
        elapse(1800); assertEquals(2, platform.agentPulses.size)
        haptics.observe(active(reply(steps = listOf(ActivityRow("call", "Search", status = "running")))), true)
        elapse(180); assertEquals(AgentHaptic.Tool, platform.agentPulses.last().first)
        elapse(300)
        haptics.observe(active(reply("New text")), true)
        assertEquals(AgentHaptic.Streaming, platform.agentPulses.last().first)
        val pulses = platform.agentPulses.size
        elapse(8000)
        assertEquals(pulses, platform.agentPulses.size, "Streaming never vibrates without new text")
        haptics.observe(active(reply("New text")), true)
        assertEquals(pulses, platform.agentPulses.size, "Repeated snapshots stay silent")
    }

    @Test fun streamingUpdatesAreThrottledAndStrengthIsConfigurable() = runTest {
        val (platform, haptics) = fixture()
        var settings = Preferences(thinkingHaptics = false, hapticStrength = "light")
        haptics.observe(active(preferences = settings), true)
        repeat(12) { index ->
            elapse(50)
            haptics.observe(active(reply("Text $index"), settings), true)
        }
        assertEquals(2, platform.agentPulses.size)
        val light = platform.agentPulses.last().second
        settings = settings.copy(hapticStrength = "strong", hapticCadence = "lively")
        elapse(300)
        haptics.observe(active(reply("More text"), settings), true)
        assertTrue(platform.agentPulses.last().second > light)
        elapse(160)
        haptics.observe(active(reply("Even more text"), settings), true)
        assertEquals(4, platform.agentPulses.size)
        assertTrue(platform.agentPulses.all { it.second in 0f..1f })
    }

    @Test fun backgroundNavigationCompletionAndQuestionsStopHeartbeats() = runTest {
        val (platform, haptics) = fixture()
        val stoppedStates = listOf(
            active().copy(busy = false), active().copy(connected = false),
            active().copy(screen = Screen.Files), active().copy(menuOpen = true),
            active(reply(live = false)), active().copy(activeJobId = null),
            active().copy(questions = listOf(BotQuestion("q", "chat", "job", "Confirm?", emptyList(), true))),
        )
        stoppedStates.forEach { state ->
            haptics.observe(active(), true); elapse(600)
            haptics.observe(state, true)
            val count = platform.agentPulses.size
            elapse(6000); assertEquals(count, platform.agentPulses.size)
        }
        haptics.observe(active(), true); elapse(600)
        platform.foreground.value = false
        haptics.observe(active(), false)
        val count = platform.agentPulses.size
        elapse(6000); assertEquals(count, platform.agentPulses.size)
        assertTrue(platform.cancelledHaptics > 0)
    }

    @Test fun everySwitchSuppressesItsOwnFeedbackAndMasterSuppressesAll() = runTest {
        val (platform, haptics) = fixture()
        val disabled = Preferences(thinkingHaptics = false, toolHaptics = false, streamingHaptics = false)
        haptics.observe(active(preferences = disabled), true); elapse(3000)
        haptics.observe(active(reply(steps = listOf(ActivityRow("call", "Tool"))), disabled), true); elapse(3000)
        haptics.observe(active(reply("Response"), disabled), true); elapse(3000)
        assertTrue(platform.agentPulses.isEmpty())
        listOf(Preferences(haptics = false), Preferences(answerHaptics = false)).forEach { p ->
            haptics.observe(active(preferences = p), true); elapse(3000)
            haptics.preview(AgentHaptic.Tool, p, true)
        }
        assertTrue(platform.agentPulses.isEmpty())
    }

    @Test fun stopCannotBeUndoneByLateStreamEventsButReconnectCanResume() = runTest {
        val (platform, haptics) = fixture()
        haptics.observe(active(), true); elapse(600)
        haptics.pause("job"); haptics.observe(active(), true)
        var count = platform.agentPulses.size
        elapse(5000); assertEquals(count, platform.agentPulses.size)
        haptics.resume("job"); haptics.observe(active(), true); elapse(600)
        assertTrue(platform.agentPulses.size > count)
        haptics.pause("job", stopped = true)
        haptics.resume("job"); haptics.observe(active(reply("Late text")), true)
        count = platform.agentPulses.size
        elapse(5000); assertEquals(count, platform.agentPulses.size)
    }

    @Test fun openingAnAlreadyStreamingChatDoesNotReplayItsText() = runTest {
        val (platform, haptics) = fixture()
        haptics.observe(active(reply("Existing streamed text")), true); elapse(5000)
        assertTrue(platform.agentPulses.isEmpty())
        haptics.observe(active(reply("Existing streamed text and a new chunk")), true)
        assertEquals(listOf(AgentHaptic.Streaming), platform.agentPulses.map { it.first })
    }

    @Test fun previewsAreSinglePatternsAndRespectAvailabilityAndForeground() = runTest {
        val (platform, haptics) = fixture()
        AgentHaptic.entries.forEach { haptics.preview(it, Preferences(), true) }
        assertEquals(AgentHaptic.entries.toList(), platform.agentPulses.map { it.first })
        elapse(5000); assertEquals(3, platform.agentPulses.size)
        haptics.preview(AgentHaptic.Tool, Preferences(), false)
        platform.supportsAgentHaptics = false
        haptics.preview(AgentHaptic.Tool, Preferences(), true)
        assertEquals(3, platform.agentPulses.size)
    }

    @Test fun oldPreferencesKeepTheirOptOutAndUseCompatibleNewDefaults() {
        val old = Json.decodeFromString<Preferences>("""{"answerHaptics":false}""")
        assertFalse(old.answerHaptics)
        assertTrue(old.thinkingHaptics && old.toolHaptics && old.streamingHaptics)
        assertEquals("medium", old.hapticStrength)
        assertEquals("balanced", old.hapticCadence)
    }

    @Test fun theStrengthSliderReplacesTheThreeStepsWithoutChangingOldChoices() = runTest {
        // An install saved before the slider keeps exactly what Light/Medium/Strong meant.
        assertEquals(.35f, Json.decodeFromString<Preferences>("""{"hapticStrength":"light"}""").hapticStrengthLevel)
        assertEquals(.65f, Preferences().hapticStrengthLevel)
        assertEquals(1f, Preferences(hapticStrength = "strong").hapticStrengthLevel)
        assertEquals(.8f, Preferences(hapticStrength = "light", hapticLevel = .8f).hapticStrengthLevel, "The slider wins once it has been moved")
        assertEquals(HapticLevelMin, Preferences(hapticLevel = -3f).hapticStrengthLevel)

        val (platform, haptics) = fixture()
        haptics.preview(AgentHaptic.Tool, Preferences(hapticLevel = .2f), true)
        haptics.preview(AgentHaptic.Tool, Preferences(hapticLevel = .9f), true)
        assertTrue(platform.agentPulses[1].second > platform.agentPulses[0].second, "A higher level must play a stronger tap")
    }

    @Test fun draggingTheSliderTapsAtExactlyTheLevelUnderTheFinger() = runTest {
        val (platform, haptics) = fixture()
        haptics.previewStrength(.42f, Preferences(), true)
        assertEquals(.42f, platform.agentPulses.single().second)
        // Same gates as the pattern previews: master switches, foreground and hardware.
        haptics.previewStrength(.9f, Preferences(answerHaptics = false), true)
        haptics.previewStrength(.9f, Preferences(haptics = false), true)
        haptics.previewStrength(.9f, Preferences(), false)
        platform.supportsAgentHaptics = false
        haptics.previewStrength(.9f, Preferences(), true)
        assertEquals(1, platform.agentPulses.size)
    }
}
