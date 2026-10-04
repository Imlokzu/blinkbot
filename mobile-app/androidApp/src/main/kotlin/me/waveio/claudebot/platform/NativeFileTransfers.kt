package me.waveio.claudebot.platform

import java.io.File

/** Pure file policy shared by native launchers and JVM regression checks. */
internal object NativeFileTransfers {
    private const val MAX_SHARE_FILES = 64
    private const val MAX_SHARE_BYTES = 80L * 1024 * 1024
    private const val SHARE_LIFETIME_MS = 24 * 60 * 60 * 1000L

    fun <T> readSelection(items: Iterable<T>, checkCancelled: () -> Unit = {},
                          read: (T, Int) -> PickedFile?): List<PickedFile> {
        val files = mutableListOf<PickedFile>()
        var remaining = PickedFileLimits.MAX_BYTES
        // Bound attempted items, not only successful reads, for hostile providers.
        for (item in items.take(PickedFileLimits.MAX_SELECTION)) {
            checkCancelled()
            if (remaining == 0) break
            val file = read(item, remaining) ?: continue
            if (file.bytes.isEmpty() || file.bytes.size > remaining) continue
            files += file
            remaining -= file.bytes.size
        }
        return files
    }

    fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() }.trim().take(180)
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"

    fun mimeType(mime: String): String = mime.trim().takeIf {
        it.length <= 127 && it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
    } ?: "application/octet-stream"

    fun valid(file: PickedFile) = file.bytes.size <= PickedFileLimits.MAX_BYTES

    /** Retain recent grants; reject additions beyond 64 files or 80 MiB instead of evicting them. */
    @Synchronized
    fun prepareShare(cache: File, file: PickedFile, now: Long = System.currentTimeMillis()): File? {
        if (!valid(file)) return null
        removeExpiredShares(cache, now)
        val root = File(cache, "native-share").apply { mkdirs() }
        val shares = root.listFiles().orEmpty()
        if (shares.size >= MAX_SHARE_FILES) return null
        val retainedBytes = shares.sumOf { share ->
            share.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        if (retainedBytes > MAX_SHARE_BYTES - file.bytes.size) return null
        val directory = java.nio.file.Files.createTempDirectory(root.toPath(), "share-").toFile()
        try {
            return File(directory, safeName(file.name)).apply { writeBytes(file.bytes) }
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun removeExpiredShares(cache: File, now: Long = System.currentTimeMillis()) {
        File(cache, "native-share").listFiles()?.forEach { directory ->
            if (directory.isDirectory && directory.name.startsWith("share-") &&
                now - directory.lastModified() >= SHARE_LIFETIME_MS) directory.deleteRecursively()
        }
    }
}
