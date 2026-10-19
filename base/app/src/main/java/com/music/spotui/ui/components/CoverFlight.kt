package com.music.spotui.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

/**
 * The cover that flies between the mini player and the full player while the player opens
 * or closes, as in the official app: the small artwork grows into the big one as the player
 * rises, and shrinks back into the mini player as it goes down.
 *
 * Both covers report where they sit; while [progress] is set the real ones are hidden and
 * [FlyingCover] draws one in between. Everything moves in the draw phase (graphicsLayer),
 * so the flight recomposes nothing.
 */
object CoverFlight {
    /** The mini player's cover, in window pixels. */
    var mini by mutableStateOf<Rect?>(null)
    /** The full player's cover once it is open, in window pixels; null when it shows none (Canvas, video, immersive). */
    var player by mutableStateOf<Rect?>(null)
    /** 0 (mini player) .. 1 (full player) while the player opens or closes; null otherwise. */
    var progress by mutableStateOf<Float?>(null)
    /**
     * How far down the sliding player is drawn right now (px), set by the player sheet: the
     * player's cover is measured on the first frame of its opening, its slide taken back out,
     * so the very first opening already knows where the cover lands.
     */
    var sheetOffsetPx: () -> Float = { 0f }

    /** Read in a draw-phase lambda: hides a real cover while its stand-in flies. */
    val flying: Boolean get() = progress != null
}

/**
 * The flying cover. Drawn inside the sliding player, so the slide's own offset
 * ([slideOffsetPx], from the same animation) is taken back out: the cover moves in window
 * coordinates, not with the sheet.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun FlyingCover(coverUri: String, slideOffsetPx: () -> Float) {
    val to = CoverFlight.player ?: return
    val from = CoverFlight.mini ?: return
    if (to.width <= 0f || to.height <= 0f) return
    val density = LocalDensity.current
    Box(
        Modifier
            .size(with(density) { to.width.toDp() }, with(density) { to.height.toDp() })
            .graphicsLayer {
                val p = CoverFlight.progress
                if (p == null) { alpha = 0f; return@graphicsLayer }
                alpha = 1f
                transformOrigin = TransformOrigin(0f, 0f)
                val left = from.left + (to.left - from.left) * p
                val top = from.top + (to.top - from.top) * p
                val width = from.width + (to.width - from.width) * p
                scaleX = width / to.width
                scaleY = scaleX
                translationX = left
                translationY = top - slideOffsetPx()
            }
            .clip(RoundedCornerShape(8.dp)),
    ) {
        GlideImage(
            model = coverUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(with(density) { to.width.toDp() }, with(density) { to.height.toDp() }),
        )
    }
}
