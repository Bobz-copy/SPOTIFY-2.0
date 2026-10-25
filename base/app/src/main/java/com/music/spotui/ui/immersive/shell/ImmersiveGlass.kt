/*
 * Adapted from BitChord (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later,
 * app/src/main/java/com/music/bitchord/ui/components/LiquidGlass.kt at edc1e6f, itself adapted
 * from EchoMusicApp/Echo-Music's GlassEffectConfig / Modifier.liquidGlass (GPL-3.0).
 * Modified for Myspoti, 2026-10: the switch is LiquidGlassPref, the fallback is Myspoti's Haze
 * frost, the page is recorded per page (each page's list) instead of around the whole app, and
 * a glass drawn inside the page it samples, or in another window, falls back to a plain fill.
 *
 * The rendering itself is Kyant0/backdrop (Apache-2.0), vendored in ./backdrop.
 */
package com.music.spotui.ui.immersive.shell

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.music.spotui.data.preferences.LiquidGlassPref
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.shell.backdrop.Backdrop
import com.music.spotui.ui.immersive.shell.backdrop.backdrops.LayerBackdrop
import com.music.spotui.ui.immersive.shell.backdrop.backdrops.rememberLayerBackdrop
import com.music.spotui.ui.immersive.shell.backdrop.drawBackdrop
import com.music.spotui.ui.immersive.shell.backdrop.effects.blur
import com.music.spotui.ui.immersive.shell.backdrop.effects.colorControls
import com.music.spotui.ui.immersive.shell.backdrop.effects.lens
import com.music.spotui.ui.immersive.shell.backdrop.highlight.Highlight
import com.music.spotui.ui.immersive.shell.backdrop.shadow.Shadow
import com.music.spotui.ui.look.rememberReduceMotion

/** Whether Liquid Glass can run here, before the user's own switch is taken into account. */
enum class LiquidGlassAvailability {
    /** Android 12+ and nothing asks for less: the switch decides. */
    AVAILABLE,

    /** The phone asks for less blur (window blurs off, battery saver) or no animation: frosted instead. */
    REDUCED,

    /** Before Android 12 there is no RenderEffect to bend the page with: frosted, and the switch is greyed. */
    UNSUPPORTED,
}

/** See [LiquidGlassAvailability]. Follows the system's blur switch live. */
@Composable
fun liquidGlassAvailability(): LiquidGlassAvailability {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return LiquidGlassAvailability.UNSUPPORTED
    val reduceMotion = rememberReduceMotion()
    val blurs = rememberWindowBlursEnabled()
    return if (reduceMotion || !blurs) LiquidGlassAvailability.REDUCED else LiquidGlassAvailability.AVAILABLE
}

/** True when the Immersive bars should be Liquid Glass: the switch is on and [liquidGlassAvailability] allows it. */
@Composable
fun rememberLiquidGlassActive(): Boolean =
    LiquidGlassPref.on && liquidGlassAvailability() == LiquidGlassAvailability.AVAILABLE

/**
 * The system's "window blurs" (off under battery saver, with the developer option, or on a device
 * whose GPU cannot afford them). The cheapest honest signal of "this phone wants less blur".
 */
@Composable
private fun rememberWindowBlursEnabled(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val context = LocalContext.current
    val wm = remember(context) { context.getSystemService(WindowManager::class.java) }
    var enabled by remember(wm) { mutableStateOf(wm?.isCrossWindowBlurEnabled ?: false) }
    DisposableEffect(wm) {
        if (wm == null) return@DisposableEffect onDispose { }
        val listener = java.util.function.Consumer<Boolean> { enabled = it }
        wm.addCrossWindowBlurEnabledListener(listener)
        onDispose { wm.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled
}

/**
 * The recorded page the Liquid Glass surfaces refract. Provided by the host (App.kt) only while
 * [rememberLiquidGlassActive] is true; null means the bars fall back to Haze's frost. Pages feed
 * it through [immersiveHazeSource], which records the page here as well when it is provided.
 */
val LocalImmersiveBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/** A page recording kept for the lifetime of the calling composition. */
@Composable
fun rememberImmersiveBackdrop(): LayerBackdrop = rememberLayerBackdrop()

/**
 * Where a glass surface composed under it also records the glass it draws (without rim or
 * shadow), so the tab bar's selection lens can refract the bar rather than the page behind it.
 */
internal val LocalGlassExport = compositionLocalOf<LayerBackdrop?> { null }

/**
 * Resolution the glass records and processes its backdrop at: a third, as BitChord (nine times
 * fewer pixels through the colour matrix, the blur and the lens; the blur hides the upscale).
 */
private const val GLASS_RESOLUTION_SCALE = 0.33f
private val GLASS_BLUR = 8.dp
private val GLASS_LENS_HEIGHT = 24.dp
private val GLASS_LENS_AMOUNT = 24.dp

/** The glass's own body over the refracted page: dark, so white text holds on any artwork. */
internal val GlassTint = Color(0xFF1C1C22).copy(alpha = 0.42f)

private val GlassShadow = Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.22f))

/**
 * Liquid Glass over [page]: vibrancy, blur and (Android 13+, rounded shapes) lens refraction, a
 * dark [tint], a rim of light and a soft shadow. Use through [immersiveGlassSurface]; this one is
 * for surfaces that need a different tint or no lens (the top bar's strip).
 */
@Composable
internal fun Modifier.immersiveLiquidGlass(
    shape: Shape,
    page: LayerBackdrop,
    tint: Color = GlassTint,
    lens: Boolean = true,
    rim: Boolean = true,
): Modifier {
    val density = LocalDensity.current
    val export = LocalGlassExport.current
    val backdrop = remember(page) { PageBackdrop(page) }
    val blurPx = with(density) { GLASS_BLUR.toPx() } * GLASS_RESOLUTION_SCALE
    val lensHeightPx = with(density) { GLASS_LENS_HEIGHT.toPx() } * GLASS_RESOLUTION_SCALE
    val lensAmountPx = with(density) { GLASS_LENS_AMOUNT.toPx() } * GLASS_RESOLUTION_SCALE
    // The lens only knows rounded rectangles (it throws on anything else).
    val bend = lens && shape is CornerBasedShape && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1.5f)
            blur(blurPx)
            if (bend) lens(refractionHeight = lensHeightPx, refractionAmount = lensAmountPx, depthEffect = true, chromaticAberration = true)
        },
        highlight = if (rim) ({ Highlight.Default }) else null,
        shadow = if (rim) ({ GlassShadow }) else null,
        onDrawSurface = { drawRect(color = tint, size = size) },
        exportedBackdrop = export,
        backdropScale = GLASS_RESOLUTION_SCALE,
    )
}

/**
 * The page under the glass, laid on the page colour so the blur never runs into transparency
 * (where it does, the sharp page shows through the glass). Draws nothing but [Immersive.GlassFill]
 * when it cannot sample the page: the glass is inside the recorded page itself (it would draw the
 * page into itself), or in another window (a sheet), whose position against the page means nothing.
 */
internal class PageBackdrop(private val page: LayerBackdrop) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val source = page.layerCoordinates
        val usable = coordinates != null && source != null && source.isAttached && coordinates.isAttached &&
            !source.isAncestorOf(coordinates) &&
            source.findRoot() === coordinates.findRoot()
        drawRect(if (usable) Immersive.Page else Immersive.GlassFill, topLeft = FAR_TOP_LEFT, size = FAR_SIZE)
        if (usable) with(page) { drawBackdrop(density, coordinates, layerBlock) }
    }

    private companion object {
        // Past every edge, whatever the scale and padding of the recording.
        val FAR_TOP_LEFT = Offset(-20_000f, -20_000f)
        val FAR_SIZE = Size(40_000f, 40_000f)
    }
}

private fun LayoutCoordinates.findRoot(): LayoutCoordinates {
    var c = this
    while (true) c = c.parentCoordinates ?: return c
}

private fun LayoutCoordinates.isAncestorOf(other: LayoutCoordinates): Boolean {
    var c: LayoutCoordinates? = other
    while (c != null) {
        if (c === this) return true
        c = c.parentCoordinates
    }
    return false
}
