package me.waveio.claudebot.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Counts app activities so QR/picker transitions cannot masquerade as task closure. */
internal class NativeForegroundTracker(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private val active = MutableStateFlow(false)
    val foreground = active.asStateFlow()
    private var started = 0
    private var hasResumed = false

    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityResumed(activity: Activity) { hasResumed = true; active.value = true }
    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && hasResumed && !activity.isChangingConfigurations) {
            // Commit permission before signaling false, even if Compose is already disposed.
            val granted = runCatching { NativeOutboxStore(application).authorizeBackground() }.getOrDefault(false)
            active.value = false
            if (granted) runCatching { NativeOutboxWork.schedule(application) }
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
