package me.waveio.claudebot.ui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCharacterSet

@OptIn(ExperimentalForeignApi::class)
internal actual fun isStreamCombiningMark(codePoint: Int): Boolean =
    NSCharacterSet.nonBaseCharacterSet.longCharacterIsMember(codePoint.toUInt())
