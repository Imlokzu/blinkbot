package me.waveio.claudebot.platform

import java.net.URI

/** Validate the native route only; the shared controller validates/exchanges codes. */
internal object NativePairingLinks {
    fun accepts(value: String): Boolean {
        if (value.length > 4096 || value.any { it.isISOControl() || it.isWhitespace() }) return false
        return try {
            val uri = URI(value)
            uri.scheme == "claudebot" && uri.rawAuthority == "pair" &&
                uri.rawPath in listOf("", "/") && uri.rawFragment == null && uri.rawQuery != null
        } catch (_: Exception) { false }
    }
}
