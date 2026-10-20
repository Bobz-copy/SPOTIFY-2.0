package com.music.spotui.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import com.music.spotui.ui.components.CoverFlight
import com.music.spotui.ui.components.FlyingCover
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import com.music.spotui.ui.screens.PlayerScreen
import kotlinx.coroutines.flow.drop

/**
 * The full player, shown over the pages instead of being one of them, like the official
 * app: pulled down, it uncovers the page it was opened from, with the mini player and the
 * tabs already in place, and closing it leaves them there.
 */
object PlayerSheet {
    val open = mutableStateOf(false)

    fun show() { open.value = true }
    fun close() { open.value = false }
}

@Composable
fun PlayerOverlay(navController: NavController) {
    // Any navigation from the player (album, artist, playlist…) lands on that page.
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.drop(1).collect { PlayerSheet.close() }
    }
    AnimatedVisibility(
        visible = PlayerSheet.open.value,
        enter = slideInVertically(tween(SHEET_IN_MS, easing = Decelerate)) { it },
        exit = slideOutVertically(tween(SHEET_OUT_MS, easing = Accelerate)) { it },
    ) {
        BackHandler { PlayerSheet.close() }
        val settled = remember { derivedStateOf { transition.currentState == transition.targetState } }
        // The sheet's own progress (same curves as its slide), for the cover's flight: read only
        // in a flow and in draw lambdas, never in composition, so the player does not recompose
        // on every frame of the slide.
        val sheet = transition.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.Visible) tween(SHEET_IN_MS, easing = Decelerate)
                else tween(SHEET_OUT_MS, easing = Accelerate)
            },
            label = "sheet",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        // Closing after a pull: the player goes down with its cover, no flight.
        val closedByPull = remember(transition.targetState) {
            transition.targetState != EnterExitState.Visible && CoverFlight.pulledPx() > 1f
        }
        androidx.compose.runtime.SideEffect {
            CoverFlight.progressSource = {
                if (transition.currentState != transition.targetState && !closedByPull) sheet.value else null
            }
        }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { CoverFlight.progressSource = { null } } }
        var heightPx by remember { mutableIntStateOf(0) }
        androidx.compose.runtime.SideEffect { CoverFlight.sheetOffsetPx = { (1f - sheet.value) * heightPx } }
        Box(Modifier.fillMaxSize().onSizeChanged { heightPx = it.height }
            .onGloballyPositioned { CoverFlight.sheetCoords = it }) {
            // The player fades with its slide (see CoverFlight.sheetAlpha); a full layer alpha only
            // while it moves, so the open player draws without an offscreen buffer.
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = if (transition.currentState != transition.targetState) CoverFlight.sheetAlpha(sheet.value) else 1f
            }) {
            CompositionLocalProvider(LocalPageSettled provides settled) {
                // A podcast episode gets the podcast player (±15 s, speed, sleep timer, no shuffle);
                // everything else keeps the music player.
                if (com.music.spotui.di.SongPlayer.isEpisode) {
                    com.music.spotui.ui.screens.PodcastPlayerScreen(navController)
                } else {
                    PlayerScreen(navController)
                }
            }
            }
            if (!com.music.spotui.di.SongPlayer.isEpisode) FlyingCover(
                coverUri = com.music.spotui.di.Graph.currentSongState.coverUri.value,
                slideOffsetPx = { (1f - sheet.value) * heightPx },
            )
        }
    }
}
