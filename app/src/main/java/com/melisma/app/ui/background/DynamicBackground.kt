package com.melisma.app.ui.background

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import com.melisma.app.settings.BackgroundStyle
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** ~30 fps. See the comment where it is used. */
private const val BACKGROUND_FRAME_INTERVAL_NANOS = 33_000_000L

/** How long Living holds the last cover while the next track's is missing. */
private const val ARTWORK_GAP_MS = 2_000L

/** The style actually drawn, after the fallbacks and the still-background rules. */
internal fun resolveStyle(
    style: BackgroundStyle,
    preferStill: Boolean,
    stillAsCover: Boolean,
    hasArtistImage: Boolean,
): BackgroundStyle = when {
    // Without a Spotify cookie there is no artist image to show, so fall back rather than render
    // an empty background.
    style == BackgroundStyle.ARTIST_HEADER && !hasArtistImage -> BackgroundStyle.COVER_ART

    // The classic field has no still form, so it swaps for the cover. Living renders one frame and
    // stops, which costs the same, unless asked for the cover instead.
    preferStill && style == BackgroundStyle.LIVING_CLASSIC -> BackgroundStyle.COVER_ART
    preferStill && stillAsCover && (style == BackgroundStyle.ANIMATED || style == BackgroundStyle.AUTO) ->
        BackgroundStyle.COVER_ART

    style == BackgroundStyle.AUTO -> BackgroundStyle.ANIMATED

    else -> style
}

/**
 * The background behind the lyrics, in whichever style is chosen.
 *
 * Living is [Kawarp], the renderer Spicy Lyrics uses. Living (classic) is what Living was before
 * it: the cover reduced to a handful of pixels and three copies of that colour field drifted,
 * rotated and scaled past each other. It is kept because it is lighter, but it does not look like
 * Spicy Lyrics — the copies are over-scaled, which pushes the cover's edges off-screen and leaves
 * its middle to average into one colour.
 */
@Composable
fun DynamicBackground(
    artwork: Bitmap?,
    colors: ArtworkColors,
    style: BackgroundStyle,
    modifier: Modifier = Modifier,
    /** Blur radius for the still styles, 0–67 px. */
    blurRadius: Int = 24,
    /**
     * Hold the background still: a floating window, or battery saver.
     *
     * Overrides an explicit choice of the animated style rather than only resolving Auto, which is
     * what it always claimed to do. A popup may be on screen above another app for hours, and
     * battery saver is the system saying plainly that this is not the moment.
     */
    preferStill: Boolean = false,
    /** When [preferStill], show Living as the still cover art rather than a frozen frame. */
    stillAsCover: Boolean = false,
    /**
     * Whether the song is actually playing.
     *
     * The drift belongs to the song, so it stops when the song does — and stopping it is the
     * difference between a paused lyrics screen costing nothing and costing three full-screen
     * layers, thirty times a second, for as long as it is left open. It resumes from where it
     * stopped rather than from zero, so a pause is a pause and not a cut.
     */
    playing: Boolean = true,
    /** The artist's image, when Spotify has given us one. */
    artistImage: Bitmap? = null,
    /**
     * Song tempo in BPM, when known.
     *
     * Paces the drift: a ballad's background should not churn at the same rate as a
     * dance track's. Clamped hard, because the point is a hint of the song's energy, not
     * a strobe.
     */
    tempoBpm: Float? = null,
) {
    val resolved = resolveStyle(style, preferStill, stillAsCover, hasArtistImage = artistImage != null)

    // A background that never changes is worth caching: promoted to its own render node,
    // the GPU keeps the rasterised result and re-blits it instead of re-drawing the
    // gradients and the upscaled artwork on every lyric frame.
    val staticModifier = modifier.fillMaxSize().graphicsLayer()

    if (resolved == BackgroundStyle.BLACK) {
        Canvas(staticModifier) { drawRect(Color.Black) }
        return
    }

    if (resolved == BackgroundStyle.COVER_ART || resolved == BackgroundStyle.ARTIST_HEADER) {
        // The real image, softened. How soft is the user's call, and the blur is done by
        // downscaling before the upscale rather than by a shader, so it costs nothing and
        // works on every API level.
        val source = if (resolved == BackgroundStyle.ARTIST_HEADER) artistImage else artwork
        StillCoverBackground(source, colors, blurRadius, staticModifier)
        return
    }

    if (resolved == BackgroundStyle.COLOR) {
        // Two flat washes and a floor gradient: the same construction as the
        // stylesheet's colour background, which uses a high-contrast panel over a base.
        Canvas(staticModifier) {
            drawRect(colors.base)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to colors.darkVibrant.copy(alpha = 0.85f),
                    1f to Color.Transparent,
                ),
            )
            drawFloorShade()
        }
        return
    }

    if (resolved == BackgroundStyle.ANIMATED) {
        KawarpBackground(
            artwork = artwork,
            colors = colors,
            moving = playing && !preferStill,
            tempoBpm = tempoBpm,
            modifier = modifier,
        )
        return
    }

    val field = remember(artwork) { artwork?.toColourField() }
    val animated = resolved == BackgroundStyle.LIVING_CLASSIC

    // Cross-fade the new cover in, matching the 850 ms cover transition.
    //
    // Driven from zero on every change rather than from a null check: once the first cover
    // was in place `current` stayed non-null, so the target stayed at 1 and no later track
    // ever faded — it simply cut. An Animatable is snapped to 0 and run up to 1 each time
    // the artwork changes, which is what the previous layer is drawn against.
    var previous by remember { mutableStateOf<Bitmap?>(null) }
    var current by remember { mutableStateOf<Bitmap?>(null) }
    val fadeAnim = remember { Animatable(0f) }
    LaunchedEffect(field) {
        if (current === field) return@LaunchedEffect
        previous = current
        current = field
        if (field == null) {
            // Nothing to fade to; let the old one go rather than holding it at full strength.
            fadeAnim.snapTo(0f)
            previous = null
            return@LaunchedEffect
        }
        fadeAnim.snapTo(0f)
        fadeAnim.animateTo(1f, tween(durationMillis = 850, easing = LinearEasing))
        // The outgoing cover is only needed while it is still visible.
        previous = null
    }
    val fade = fadeAnim.value

    // 120 BPM is the neutral point; the clamp keeps a very slow or very fast song from
    // making the background either static or frantic.
    val driftSpeed = ((tempoBpm ?: 120f) / 120f).coerceIn(0.65f, 1.7f)
    val currentSpeed by rememberUpdatedState(driftSpeed)

    // Drift time, already scaled by tempo. Scaled as it accumulates rather than when drawn, so a
    // new track's tempo changes the pace from here on instead of rescaling everything so far.
    var timeSeconds by remember { mutableStateOf(0f) }

    // Elapsed *playing* time, accumulated frame by frame rather than measured from a start
    // stamp: a paused song stops the clock, and taking the difference from a fixed start would
    // make it lurch forward by the length of the pause the moment the music came back.
    LaunchedEffect(animated, playing) {
        if (!animated || !playing) return@LaunchedEffect
        var previousFrame = 0L
        var unpublished = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (previousFrame != 0L) unpublished += nanos - previousFrame
                previousFrame = nanos
                // The layers drift on 30–70 second orbits, so publishing a new time every
                // frame would redraw three full-screen textured layers for a change nobody
                // can see. A third of the frames is indistinguishable and costs a third as
                // much — and on this screen the background is the expensive part, not the
                // lyrics.
                if (unpublished >= BACKGROUND_FRAME_INTERVAL_NANOS) {
                    timeSeconds += unpublished / 1_000_000_000f * currentSpeed
                    unpublished = 0L
                }
            }
        }
    }

    val paint = remember {
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(2.35f)
                    // Multiply everything down: the lyrics need the headroom.
                    postConcat(ColorMatrix(floatArrayOf(
                        0.62f, 0f, 0f, 0f, 0f,
                        0f, 0.62f, 0f, 0f, 0f,
                        0f, 0f, 0.62f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    )))
                },
            )
        }
    }
    val matrix = remember { Matrix() }

    Canvas(modifier.fillMaxSize()) {
        drawRect(colors.base)

        val bitmap = current
        if (bitmap != null) {
            drawIntoCanvas { canvas ->
                val previousBitmap = previous
                if (previousBitmap != null && fade < 1f) {
                    paint.alpha = ((1f - fade) * 255).toInt()
                    drawField(canvas.nativeCanvas, previousBitmap, matrix, paint, timeSeconds)
                }
                paint.alpha = (fade.coerceIn(0f, 1f) * 255).toInt()
                drawField(canvas.nativeCanvas, bitmap, matrix, paint, timeSeconds)
                paint.alpha = 255
            }
        }

        drawFloorShade()
    }
}

/**
 * Album art held still and blurred — Spicy Lyrics' "Cover Art" static background.
 *
 * The blur comes from how far the bitmap is reduced before it is scaled back up, so a
 * radius of 0 leaves the artwork nearly sharp and 67 leaves a colour wash.
 */
@Composable
private fun StillCoverBackground(
    artwork: Bitmap?,
    colors: ArtworkColors,
    blurRadius: Int,
    modifier: Modifier,
) {
    val reduced = remember(artwork, blurRadius) { artwork?.reduced(blurRadius) }
    // Already promoted by the caller; `modifier` arrives with fillMaxSize applied.
    val paint = remember {
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(1.7f)
                    postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                0.55f, 0f, 0f, 0f, 0f,
                                0f, 0.55f, 0f, 0f, 0f,
                                0f, 0f, 0.55f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f,
                            ),
                        ),
                    )
                },
            )
        }
    }
    val matrix = remember { Matrix() }

    Canvas(modifier) {
        drawRect(colors.base)
        if (reduced != null) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                // Over-scale slightly so the softened edges never show.
                val scale = 1.15f * maxOf(
                    native.width.toFloat() / reduced.width,
                    native.height.toFloat() / reduced.height,
                )
                matrix.reset()
                matrix.postScale(scale, scale)
                matrix.postTranslate(
                    native.width / 2f - reduced.width * scale / 2f,
                    native.height / 2f - reduced.height * scale / 2f,
                )
                native.drawBitmap(reduced, matrix, paint)
            }
        }
        drawFloorShade()
    }
}

/** Downscale to the size a given blur radius implies. Bilinear upscaling does the rest. */
private fun Bitmap.reduced(blurRadius: Int): Bitmap? = runCatching {
    val target = (320f / (1f + blurRadius / 4f)).toInt().coerceIn(6, 320)
    val aspect = height.toFloat() / width
    Bitmap.createScaledBitmap(this, target, (target * aspect).toInt().coerceAtLeast(1), true)
}.getOrNull()

/**
 * Three copies of the colour field, each on its own slow orbit.
 *
 * The periods are long (30–70 s) and deliberately not multiples of one another, so the
 * composition never visibly loops while a song plays.
 */
private fun drawField(
    canvas: android.graphics.Canvas,
    bitmap: Bitmap,
    matrix: Matrix,
    paint: Paint,
    time: Float,
) {
    val width = canvas.width.toFloat()
    val height = canvas.height.toFloat()
    if (width <= 0f || height <= 0f) return

    val layers = 3
    val baseAlpha = paint.alpha

    for (layer in 0 until layers) {
        val phase = layer * 2.1f
        val rotationPeriod = 47f + layer * 11f
        val driftPeriod = 31f + layer * 9f

        // Over-scale so the drifting copies never expose an edge.
        val scale = (2.6f + 0.35f * sin(time / 17f + phase)) *
            maxOf(width / bitmap.width, height / bitmap.height)

        val angle = 360f * ((time / rotationPeriod + layer * 0.33f) % 1f)
        val driftX = 0.12f * width * sin(time / driftPeriod + phase)
        val driftY = 0.12f * height * cos(time / (driftPeriod * 1.3f) + phase)

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            width / 2f - bitmap.width * scale / 2f + driftX,
            height / 2f - bitmap.height * scale / 2f + driftY,
        )
        matrix.postRotate(angle, width / 2f, height / 2f)

        paint.alpha = (baseAlpha * if (layer == 0) 1f else 0.55f).toInt()
        canvas.drawBitmap(bitmap, matrix, paint)
    }
    paint.alpha = baseAlpha
}

/**
 * Living: the [Kawarp] port, rendered small off the main thread and scaled up.
 *
 * [moving] false renders what is needed — the current frame, or the rest of a crossfade — and
 * stops, so a paused song or battery saver costs nothing per frame. The clock is playback time,
 * scaled by tempo the way Spicy Lyrics scales it, and survives a pause.
 */
@Composable
private fun KawarpBackground(
    artwork: Bitmap?,
    colors: ArtworkColors,
    moving: Boolean,
    tempoBpm: Float?,
    modifier: Modifier,
) {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var current by remember { mutableStateOf<FloatArray?>(null) }
    var previous by remember { mutableStateOf<FloatArray?>(null) }
    var transitionStartMs by remember { mutableStateOf(0L) }

    LaunchedEffect(artwork) {
        val source = artwork
        if (source == null) {
            // Usually the next cover on its way: a player that publishes a URI sends the track
            // before its bitmap, and holding the last one keeps the crossfade. A cover that never
            // comes must not leave the previous track's showing.
            delay(ARTWORK_GAP_MS)
            previous = null
            current = null
            return@LaunchedEffect
        }
        val blurred = withContext(Dispatchers.Default) { runCatching { source.kawarpField() }.getOrNull() }
            ?: return@LaunchedEffect
        // The first cover appears at once; later ones cross-fade in, as in Kawarp.
        previous = current
        current = blurred
        transitionStartMs = android.os.SystemClock.uptimeMillis()
    }

    val frame = remember(size) {
        if (size.width <= 0 || size.height <= 0) {
            null
        } else if (size.width >= size.height) {
            val h = (Kawarp.OUTPUT_LONG_SIDE * size.height.toFloat() / size.width).roundToInt()
            Bitmap.createBitmap(Kawarp.OUTPUT_LONG_SIDE, h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        } else {
            val w = (Kawarp.OUTPUT_LONG_SIDE * size.width.toFloat() / size.height).roundToInt()
            Bitmap.createBitmap(w.coerceAtLeast(1), Kawarp.OUTPUT_LONG_SIDE, Bitmap.Config.ARGB_8888)
        }
    }
    var frameVersion by remember { mutableStateOf(0) }
    // The last bitmap actually rendered into. A resize allocates a new, empty one, which a paused
    // background may not render into until the size settles; until then the old one is drawn.
    var shownFrame by remember { mutableStateOf<Bitmap?>(null) }
    var clock by remember { mutableStateOf(0f) }
    // Spicy Lyrics' speed: tempo over 120, clamped. Without a tempo it runs at 1.
    val speed = tempoBpm?.let { (it / 120f).coerceIn(0.1f, 3f) } ?: 1f

    LaunchedEffect(frame, current, moving, speed) {
        val bitmap = frame ?: return@LaunchedEffect
        val cover = current ?: return@LaunchedEffect
        val pixels = IntArray(bitmap.width * bitmap.height)
        var lastFrame = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (lastFrame != 0L && now - lastFrame < BACKGROUND_FRAME_INTERVAL_NANOS) continue
            if (moving && lastFrame != 0L) clock += (now - lastFrame) / 1_000_000_000f * speed
            lastFrame = now

            val outgoing = previous
            val progress = (android.os.SystemClock.uptimeMillis() - transitionStartMs).toFloat() /
                Kawarp.TRANSITION_MS
            val blending = outgoing != null && progress < 1f
            val time = clock
            withContext(Dispatchers.Default) {
                if (blending) {
                    Kawarp.render(pixels, bitmap.width, bitmap.height, outgoing!!, cover, Kawarp.ease(progress), time)
                } else {
                    Kawarp.render(pixels, bitmap.width, bitmap.height, cover, null, 0f, time)
                }
            }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            shownFrame = bitmap
            frameVersion++

            if (!blending) previous = null
            if (!moving && !blending) break
        }
    }

    val paint = remember { Paint().apply { isFilterBitmap = true } }
    val bounds = remember { android.graphics.Rect() }
    Canvas(modifier.fillMaxSize().onSizeChanged { size = it }) {
        drawRect(colors.base)
        val bitmap = shownFrame
        // Read so a new frame redraws this layer without recomposing anything.
        if (bitmap != null && frameVersion > 0 && current != null) {
            bounds.set(0, 0, this.size.width.toInt(), this.size.height.toInt())
            drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, null, bounds, paint) }
        }
        drawFloorShade()
    }
}

/** The cover as [Kawarp] wants it: blurred once, at 128×128. */
private fun Bitmap.kawarpField(): FloatArray {
    // Hardware bitmaps cannot be read back, and a large cover is only reduced to 128 anyway.
    val longest = maxOf(width, height)
    val readable = when {
        longest > 512 -> Bitmap.createScaledBitmap(
            if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this,
            (width * 512f / longest).roundToInt().coerceAtLeast(1),
            (height * 512f / longest).roundToInt().coerceAtLeast(1),
            true,
        )
        config == Bitmap.Config.HARDWARE -> copy(Bitmap.Config.ARGB_8888, false)
        else -> this
    }
    val pixels = IntArray(readable.width * readable.height)
    readable.getPixels(pixels, 0, readable.width, 0, 0, readable.width, readable.height)
    return Kawarp.blurred(pixels, readable.width, readable.height)
}

/**
 * The dark floor that keeps the now-playing bar legible over any artwork, plus a lighter
 * wash at the top for the controls.
 *
 * Both are declared without explicit coordinates so Compose resolves them against the
 * draw size — which means they can be built once and reused on every frame rather than
 * allocating two full-screen gradients per frame.
 */
private val FLOOR_SHADE = Brush.verticalGradient(
    0.45f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.55f),
)

private val CEILING_SHADE = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.3f),
    0.28f to Color.Transparent,
)

internal fun DrawScope.drawFloorShade() {
    drawRect(brush = CEILING_SHADE)
    drawRect(brush = FLOOR_SHADE)
}

/**
 * Reduce the cover to a colour field.
 *
 * Two steps down and one back up: scaling to 5 px throws away every detail, and the
 * halfway stop keeps the result from collapsing to a single average colour. Whatever
 * comes out is a smooth blend of the record's palette, which is all the background is.
 */
private fun Bitmap.toColourField(size: Int = 10): Bitmap? = runCatching {
    val stage = Bitmap.createScaledBitmap(this, size * 3, size * 3, true)
    val tiny = Bitmap.createScaledBitmap(stage, size / 2, size / 2, true)
    val field = Bitmap.createScaledBitmap(tiny, size, size, true)
    if (stage !== field) stage.recycle()
    if (tiny !== field) tiny.recycle()
    field
}.getOrNull()
