package com.music.spotui.ui.immersive.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import com.music.spotui.ui.look.rememberReduceMotion

/**
 * The motion constants of the Immersive look in one place, so screens share one feel instead of
 * each picking numbers. The springs are BitChord's (medium-low stiffness for moving things,
 * high for press feedback); pages push like a navigation stack, the old page sliding a third of
 * the way as the new one covers it.
 */
object ImmersiveMotion {
    /** Placement of rows in a list (reorder, insert, filter). */
    fun <T> placementSpring() = spring<T>(stiffness = Spring.StiffnessMediumLow)

    /**
     * The floating chrome folding and unfolding (the tab bar minimizing to a pill, the top bar's
     * controls shrinking): a soft spring with a hint of overshoot. Snaps under "reduce motion".
     */
    fun <T> chromeSpring(reduce: Boolean = false): androidx.compose.animation.core.AnimationSpec<T> =
        if (reduce) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

    /** A page pushed or popped by the nav host. */
    private const val PAGE_MS = 380

    /**
     * Enter/exit specs for `NavHost` in the Immersive look. With "reduce motion" on they are a
     * short fade. Pass them as `enterTransition`, `exitTransition`, `popEnterTransition`,
     * `popExitTransition`.
     */
    object Pages {
        fun enter(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
            if (reduce) fadeIn(tween(150)) else slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))) { it } +
                fadeIn(tween(PAGE_MS / 2))
        }

        fun exit(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
            if (reduce) fadeOut(tween(150)) else slideOutHorizontally(tween(PAGE_MS)) { -it / 3 } + fadeOut(tween(PAGE_MS))
        }

        fun popEnter(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
            if (reduce) fadeIn(tween(150)) else slideInHorizontally(tween(PAGE_MS)) { -it / 3 } + fadeIn(tween(PAGE_MS))
        }

        fun popExit(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
            if (reduce) fadeOut(tween(150)) else slideOutHorizontally(tween(PAGE_MS)) { it } + fadeOut(tween(PAGE_MS / 2))
        }
    }
}

/**
 * Items of a lazy list glide to their new place (no cross-fade, the list stays readable), and
 * stay put under "reduce motion". Use on every row of an Immersive list.
 */
@Composable
fun LazyItemScope.immersiveItemPlacement(): Modifier {
    if (rememberReduceMotion()) return Modifier
    return Modifier.animateItem(
        fadeInSpec = null,
        fadeOutSpec = null,
        placementSpec = ImmersiveMotion.placementSpring(),
    )
}
