package com.music.spotui.di

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import java.lang.ref.WeakReference

/** Light, system-native haptics for user actions (respects the system haptics setting). */
object Haptics {
    private var view: WeakReference<View>? = null

    fun attach(v: View) { view = WeakReference(v) }

    private fun perform(constant: Int, fallback: Int = HapticFeedbackConstants.CONTEXT_CLICK) {
        val v = view?.get() ?: return
        // Only while the app is in front (no buzz for notification / headset commands).
        if (v.windowVisibility != View.VISIBLE || !v.hasWindowFocus()) return
        v.post { if (!v.performHapticFeedback(constant)) v.performHapticFeedback(fallback) }
    }

    /** Buttons: play/pause, next/previous, add to queue. */
    fun click() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    /** Toggles: like, shuffle, repeat. */
    fun toggle(on: Boolean) = perform(
        if (Build.VERSION.SDK_INT >= 34) (if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
        else HapticFeedbackConstants.CLOCK_TICK
    )

    /**
     * One tick of a rising buzz, [level] 0..1 (the debug button while it is held): stronger
     * as the hold goes on, so the finger feels it is getting somewhere.
     */
    fun ramp(level: Float) {
        val v = view?.get() ?: return
        val vibrator = (if (Build.VERSION.SDK_INT >= 31)
            v.context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") v.context.getSystemService(android.os.Vibrator::class.java)) ?: return
        val l = level.coerceIn(0.05f, 1f)
        runCatching {
            if (Build.VERSION.SDK_INT >= 30 && vibrator.areAllPrimitivesSupported(android.os.VibrationEffect.Composition.PRIMITIVE_TICK)) {
                vibrator.vibrate(android.os.VibrationEffect.startComposition()
                    .addPrimitive(android.os.VibrationEffect.Composition.PRIMITIVE_TICK, l).compose())
            } else {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(12, (40 + 215 * l).toInt().coerceIn(1, 255)))
            }
        }
    }

    /** A completed action (queued, removed, transferred). */
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
}
