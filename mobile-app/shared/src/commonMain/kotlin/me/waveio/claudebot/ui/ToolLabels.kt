package me.waveio.claudebot.ui

import androidx.compose.runtime.Composable

/** Match dashboard tool names while keeping unknown server names recognizable. */
fun bareToolName(label: String): String = label.substringAfterLast("__").substringAfterLast("--").replace('-', '_')

private val toolAliases = mapOf("read" to "workspace_read", "write" to "workspace_write", "edit" to "workspace_write", "glob" to "workspace_list")
private val namedTools = setOf("python_calculate", "web_search", "image_search", "facts", "weather", "currency", "memory_search", "workspace_read", "workspace_write", "workspace_list", "workspace_show", "workspace_info", "ask_question", "todo_list", "show_choice", "play_music", "stop_music", "play_video", "listen_to_video", "video_control", "bash", "exec", "grep")

fun toolLabel(label: String, strings: LocaleText): String {
    val bare = bareToolName(label)
    val name = toolAliases[bare] ?: bare
    return if (name in namedTools) strings.get("tool.$name") else bare.replace('_', ' ')
}

@Composable fun toolLabel(label: String): String = toolLabel(label, LocalText.current)
