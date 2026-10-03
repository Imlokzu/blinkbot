package me.waveio.claudebot.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Installed host metadata; absent permission flags never enable selection. */
@Serializable
data class MobileSkill(
    val name: String,
    val description: String = "",
    val emoji: String = "",
    val source: String = "",
    val homepage: String = "",
    val invocation: String = "",
    val bundled: Boolean = false,
    val enabled: Boolean = false,
    val eligible: Boolean = false,
    @SerialName("user_invocable") val userInvocable: Boolean = false,
    @SerialName("blocked_by_allowlist") val blockedByAllowlist: Boolean = false,
    @SerialName("blocked_by_agent_filter") val blockedByAgentFilter: Boolean = false,
    @SerialName("model_visible") val modelVisible: Boolean = false,
    @SerialName("command_visible") val commandVisible: Boolean = false,
    val selectable: Boolean = false,
    val missing: List<String> = emptyList(),
)

@Serializable
data class SkillCatalog(val skills: List<MobileSkill> = emptyList())
