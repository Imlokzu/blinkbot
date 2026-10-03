package me.waveio.claudebot.platform

import java.io.File

/** Process death skips lifecycle callbacks, so the next launch removes captures. */
internal object NativeTemporaryFiles {
    fun removeAbandoned(cache: File, revokeCameraGrant: (File) -> Unit) {
        File(cache, "native-camera").listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("capture-") && file.extension == "jpg") {
                runCatching { revokeCameraGrant(file) }
                runCatching { file.delete() }
            }
        }
        listOf(cache, File(cache, "native-recordings")).forEach { directory ->
            directory.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("dictation-") && file.extension == "m4a") {
                    runCatching { file.delete() }
                }
            }
        }
    }
}
