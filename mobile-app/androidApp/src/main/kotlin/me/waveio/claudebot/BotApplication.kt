package me.waveio.claudebot

import android.app.Application
import androidx.work.ExistingWorkPolicy
import me.waveio.claudebot.platform.NativeOutboxStore
import me.waveio.claudebot.platform.NativeOutboxWork
import me.waveio.claudebot.platform.NativeForegroundTracker

class BotApplication : Application() {
    internal val nativeForeground by lazy { NativeForegroundTracker(this) }
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(nativeForeground)
        // WorkManager's initializer runs first; recover eligible work on a normal process launch.
        runCatching { NativeOutboxStore(this).initialize(); NativeOutboxWork.schedule(this, ExistingWorkPolicy.KEEP) }
    }
}
