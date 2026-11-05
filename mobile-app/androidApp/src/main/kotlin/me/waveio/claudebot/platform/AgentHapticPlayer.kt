package me.waveio.claudebot.platform

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import me.waveio.claudebot.state.AgentHaptic

/** Short hardware primitives preserve a crisp edge; never use a repeating buzz. */
internal class AgentHapticPlayer(private val view: View, private val foreground: () -> Boolean) {
    private val context = view.context
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else context.getSystemService(Vibrator::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var secondTap: Runnable? = null
    val available: Boolean get() = vibrator?.hasVibrator() == true

    private fun allowed(): Boolean = foreground() && view.hasWindowFocus() && view.isHapticFeedbackEnabled &&
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    fun play(kind: AgentHaptic, intensity: Float) {
        if (!available || !allowed()) return
        cancel()
        val strength = intensity.coerceIn(.05f, 1f)
        val motor = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            val primitive = if (kind == AgentHaptic.Streaming) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
            if (motor.areAllPrimitivesSupported(primitive)) {
                val effect = VibrationEffect.startComposition().addPrimitive(primitive, strength)
                if (kind == AgentHaptic.Tool) effect.addPrimitive(primitive, strength * .7f, 45)
                vibrate(effect.compose())
                return
            }
        }
        // Without composition primitives, a motor with amplitude control still scales a short
        // one-shot, so the strength slider is felt; predefined effects below ignore strength.
        if (motor.hasAmplitudeControl()) {
            val amplitude = (strength * 255).toInt().coerceIn(1, 255)
            val length = if (kind == AgentHaptic.Streaming) 8L else 14L
            vibrate(if (kind == AgentHaptic.Tool) VibrationEffect.createWaveform(longArrayOf(0, length, 45, length), intArrayOf(0, amplitude, 0, (amplitude * .7f).toInt().coerceAtLeast(1)), -1)
                else VibrationEffect.createOneShot(length, amplitude))
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val effect = when (kind) {
                AgentHaptic.Tool -> VibrationEffect.EFFECT_DOUBLE_CLICK
                AgentHaptic.Streaming -> VibrationEffect.EFFECT_TICK
                AgentHaptic.Thinking -> if (strength < .4f) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK
            }
            vibrate(VibrationEffect.createPredefined(effect))
        } else {
            val effect = if (kind == AgentHaptic.Tool) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK
            view.performHapticFeedback(effect)
            if (kind == AgentHaptic.Tool) {
                val repeat = Runnable { secondTap = null; if (allowed()) view.performHapticFeedback(effect) }
                secondTap = repeat
                handler.postDelayed(repeat, 45)
            }
        }
    }

    private fun vibrate(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else vibrator?.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
    }

    fun cancel() {
        secondTap?.let(handler::removeCallbacks)
        secondTap = null
        runCatching { vibrator?.cancel() }
    }
}
