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
    /**
     * Where the sheet is (0 .. 1) while it moves, null when still: set by the sheet, read
     * straight from its transition in draw lambdas. Published by an effect it came one frame
     * late, and that first frame showed the real covers and the flying one at once (a flash).
     */
    var progressSource: () -> Float? = { null }
    /** 0 (mini player) .. 1 (full player) while the player opens or closes; null otherwise. */
    val progress: Float? get() = progressSource()?.takeIf { mini != null && player != null }
    /**
     * How far down the sliding player is drawn right now (px), set by the player sheet: the
     * player's cover is measured on the first frame of its opening, its slide taken back out,
     * so the very first opening already knows where the cover lands.
     */
    var sheetOffsetPx: () -> Float = { 0f }
    /**
     * The sliding player's own layout: the cover is measured against it, so its target does not
     * move while the sheet slides (measured against the window it was re-measured every frame,
     * a frame off the slide, and the flying cover visibly jumped in size on the way).
     */
    var sheetCoords: androidx.compose.ui.layout.LayoutCoordinates? = null
    /**
     * How far the user's finger has pulled the player down (px), set by the player. A player
     * closed by that pull leaves with its cover, as in the official app: no flight then (the
     * flight started from where the cover would be without the pull, and a cover detached from
     * the sliding player showed above it — captured frame by frame on the test phone).
     */
    var pulledPx: () -> Float = { 0f }

    /** Read in a draw-phase lambda: hides a real cover while its stand-in flies. */
    val flying: Boolean get() = progress != null

    // Cross-fades at both ends of the flight (the owner's call: hard cuts between the real
    // covers and the flying one read as the motion being out of step). Over the last 20 % the
    // player's cover fades in as the flying one fades out, and over the first 20 % the same with
    // the mini player's; each reads [progress] in a draw lambda.
    private const val BLEND = 0.2f
    /** Opacity of the full player's own cover. */
    fun playerCoverAlpha(): Float = progress?.let { ((it - (1f - BLEND)) / BLEND).coerceIn(0f, 1f) } ?: 1f
    /** Opacity of the mini player's own cover. */
    fun miniCoverAlpha(): Float = progress?.let { ((BLEND - it) / BLEND).coerceIn(0f, 1f) } ?: 1f
    /** Opacity of the flying cover. */
    fun flyingAlpha(): Float = progress?.let { 1f - maxOf(playerCoverAlpha(), miniCoverAlpha()) } ?: 0f
    /**
     * Opacity of the player's background and controls while it slides: they fade in over the
     * first 60 % of the opening and out over the last 60 % of the closing, so the sheet never
     * arrives or leaves as a hard block.
     */
    fun sheetAlpha(p: Float): Float = (p / 0.6f).coerceIn(0f, 1f)
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
                alpha = CoverFlight.flyingAlpha()
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
        val context = androidx.compose.ui.platform.LocalContext.current
        GlideImage(
            model = coverUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(with(density) { to.width.toDp() }, with(density) { to.height.toDp() }),
            // The mini player's own small picture (already in memory) until the big one is
            // there: a cover that had to load first left an empty square flying (a flash).
            requestBuilderTransform = {
                it.thumbnail(com.bumptech.glide.Glide.with(context).load(coverUri)
                    .override(from.width.toInt().coerceAtLeast(1), from.height.toInt().coerceAtLeast(1)).optionalCenterCrop())
            },
        )
    }
}
