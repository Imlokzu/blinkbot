package me.waveio.claudebot.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WebPreview(
    val ready: Boolean,
    val root: String,
    val entry: String? = null,
    @SerialName("project_path") val projectPath: String,
    val kind: String = "web",
    val reason: String? = null,
    val buildable: Boolean = false,
)

/** Resources cross the native boundary without exposing transport credentials. */
data class WebPreviewResource(val bytes: ByteArray, val mimeType: String, val status: Int = 200)
