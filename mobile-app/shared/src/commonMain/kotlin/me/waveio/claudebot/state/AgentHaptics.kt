package me.waveio.claudebot.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.waveio.claudebot.platform.PlatformBridge

enum class AgentHaptic { Thinking, Tool, Streaming }

/** One foreground conversation owns feedback. Streaming pulses require new text. */
internal class AgentHaptics(
    private val scope: CoroutineScope,
    private val platform: PlatformBridge,
    private val now: () -> Long = platform::nowMillis,
) {
    private var owner: Pair<String, String>? = null
    private var phase: AgentHaptic? = null
    private var previousText = ""
    private var previousNotes = emptyList<String>()
    private var preferences = Preferences()
    private var heartbeat: Job? = null
    private var lastPulse: Long? = null
    private val pausedJobs = mutableSetOf<String>()
    private val stoppedJobs = mutableSetOf<String>()

    fun observe(state: AppState, foreground: Boolean) {
        val job = state.activeJobId
        val row = state.messages.firstOrNull { it.id == "a-$job" || it.presentationId == "a-$job" }
        val allowed = platform.supportsAgentHaptics && foreground && state.connected && state.screen == Screen.Chat &&
            !state.menuOpen && state.openFile == null && state.previewTitle == null && state.busy && job != null &&
            state.preferences.haptics && state.preferences.answerHaptics && row?.live != false &&
            state.questions.none { it.jobId == job } && job !in pausedJobs && job !in stoppedJobs
        if (!allowed) {
            if (preferences != state.preferences || !foreground) platform.cancelAgentHaptics()
            preferences = state.preferences
            stop()
            return
        }
        val identity = state.sessionId to job!!
        val fresh = identity != owner
        val changedPreferences = preferences != state.preferences
        val nextText = row?.text.orEmpty()
        val notes = row?.parts.orEmpty().filter { it.note || it.noteId != null }.map { it.text }
        val newText = !fresh && nextText.isNotBlank() && nextText != previousText
        val activeTools = row?.steps.orEmpty().any { it.status in setOf("running", "active", "pending") }
        val next = when {
            activeTools -> AgentHaptic.Tool
            newText -> AgentHaptic.Streaming
            fresh -> if (nextText.isBlank()) AgentHaptic.Thinking else AgentHaptic.Streaming
            phase == AgentHaptic.Tool || notes != previousNotes -> AgentHaptic.Thinking
            else -> phase ?: AgentHaptic.Thinking
        }
        owner = identity
        previousText = nextText
        previousNotes = notes
        preferences = state.preferences
        if (fresh || next != phase || changedPreferences) {
            heartbeat?.cancel()
            platform.cancelAgentHaptics()
            phase = next
            heartbeat = if (next != AgentHaptic.Streaming && enabled(next, preferences)) scope.launch {
                // Skip a thinking pulse for replies that begin almost immediately.
                delay(if (next == AgentHaptic.Thinking) 500L else 120L)
                while (isActive) {
                    pulse(next)
                    delay(interval(next, preferences))
                }
            } else null
        }
        if (newText && next == AgentHaptic.Streaming) pulse(next)
    }

    fun pause(jobId: String, stopped: Boolean = false) {
        if (stopped) stoppedJobs += jobId
        pausedJobs += jobId
        if (owner?.second == jobId) stop()
    }

    fun resume(jobId: String) { pausedJobs -= jobId }

    fun reset() { stop(); pausedJobs.clear(); stoppedJobs.clear(); lastPulse = null }

    fun preview(kind: AgentHaptic, settings: Preferences, foreground: Boolean) {
        if (!platform.supportsAgentHaptics || !foreground || !settings.haptics || !settings.answerHaptics) return
        platform.cancelAgentHaptics()
        platform.agentHaptic(kind, intensity(kind, settings))
    }

    private fun pulse(kind: AgentHaptic) {
        if (!enabled(kind, preferences) || !platform.foreground.value) return
        val time = now()
        val minimum = if (kind == AgentHaptic.Streaming) interval(kind, preferences) else 100L
        if (lastPulse?.let { time >= it && time - it < minimum } == true) return
        lastPulse = time
        platform.agentHaptic(kind, intensity(kind, preferences))
    }

    fun stop() {
        if (owner != null || heartbeat != null) platform.cancelAgentHaptics()
        heartbeat?.cancel(); heartbeat = null
        owner = null; phase = null; previousText = ""; previousNotes = emptyList()
    }

    companion object {
        private fun enabled(kind: AgentHaptic, p: Preferences) = when (kind) {
            AgentHaptic.Thinking -> p.thinkingHaptics
            AgentHaptic.Tool -> p.toolHaptics
            AgentHaptic.Streaming -> p.streamingHaptics
        }
        private fun interval(kind: AgentHaptic, p: Preferences): Long = when (p.hapticCadence) {
            "calm" -> if (kind == AgentHaptic.Streaming) 500L else 2600L
            "lively" -> if (kind == AgentHaptic.Streaming) 160L else 1100L
            else -> if (kind == AgentHaptic.Streaming) 280L else 1800L
        }
        private fun intensity(kind: AgentHaptic, p: Preferences): Float {
            val strength = when (p.hapticStrength) { "light" -> .35f; "strong" -> 1f; else -> .65f }
            return strength * when (kind) { AgentHaptic.Thinking -> .65f; AgentHaptic.Tool -> 1f; AgentHaptic.Streaming -> .45f }
        }
    }
}
