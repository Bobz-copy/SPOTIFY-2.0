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

    /** A completed action (queued, removed, transferred). */
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)
}
