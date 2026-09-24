package com.melisma.app.ui.background

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.melisma.app.settings.CanvasMode
import java.io.File

/**
 * Where a Canvas sits.
 *
 * Every Canvas is portrait, 9:16. Filled across a landscape phone it is cropped to the middle
 * quarter of its height at four times its size.
 */
enum class CanvasFraming {
    /** Cropped to fill the screen. */
    FILL,

    /** Full height down the middle, edges faded into the background either side. */
    COLUMN,

    /** Cropped to the bounds it is given, fading out downward: Cinema's side panel. */
    CARD,
}

/**
 * A Spotify Canvas as the background: muted, looping, placed by [framing].
 *
 * [poster], a still of the Canvas, shows while [file] downloads; the video fades in over it on its
 * first decoded frame. The whole layer fades in over whatever background is beneath it, so a slow
 * load, or a video that will not play with no still to stand in, simply leaves that background
 * showing. [onShowing] reports when anything of it is showing, so what it covers can stop
 * animating.
 *
 * [blurred] uses a render effect, which Android 12 introduced; before that it plays unblurred.
 */
@Composable
fun CanvasVideoBackground(
    file: File?,
    poster: Bitmap?,
    blurred: Boolean,
    playing: Boolean,
    onShowing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    framing: CanvasFraming = CanvasFraming.FILL,
) {
    val playback = remember(file) { file?.let(::CanvasPlayback) }
    val reportShowing by rememberUpdatedState(onShowing)
    var videoShown by remember(file) { mutableStateOf(false) }
    playback?.onFirstFrame = { videoShown = true }
    playback?.onFailure = { videoShown = false }
    val showing = videoShown || poster != null

    LaunchedEffect(showing) { reportShowing(showing) }
    DisposableEffect(Unit) { onDispose { reportShowing(false) } }
    DisposableEffect(playback) { onDispose { playback?.release() } }

    // Paused music pauses the video; so does the screen going away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val wantsToPlay by rememberUpdatedState(playing)
    DisposableEffect(lifecycle, playback) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> playback?.setPlaying(false)
                Lifecycle.Event.ON_START -> playback?.setPlaying(wantsToPlay)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playback, playing) { playback?.setPlaying(playing) }

    val alpha by animateFloatAsState(if (showing) 1f else 0f, tween(durationMillis = 600), label = "canvas")
    val videoAlpha by animateFloatAsState(if (videoShown) 1f else 0f, tween(durationMillis = 600), label = "video")
    val still = remember(poster) { poster?.asImageBitmap() }

    val video = @Composable {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (blurred) Modifier.blur(28.dp, BlurredEdgeTreatment.Rectangle) else Modifier),
        ) {
            if (still != null) {
                Image(still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (playback != null) {
                key(playback) {
                    AndroidView(
                        factory = { context -> TextureView(context).apply { playback.attach(this) } },
                        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = videoAlpha },
                    )
                }
            }
        }
    }
    // Spicy Lyrics dims a Canvas to brightness(0.65); the lyrics need the same headroom here.
    val dim = @Composable {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.35f))
            drawFloorShade()
        }
    }

    when (framing) {
        CanvasFraming.FILL -> Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            video()
            dim()
        }

        CanvasFraming.COLUMN -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(PORTRAIT, matchHeightConstraintsFirst = true)
                    .mask(alpha, Brush.horizontalGradient(0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent)),
            ) {
                video()
                dim()
            }
        }

        // Spicy Lyrics masks the Canvas in Spotify's own side panel the same way, fading it out
        // toward the track details below it.
        CanvasFraming.CARD -> Box(
            modifier
                .clip(RoundedCornerShape(16.dp))
                .mask(alpha, Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black, 1f to Color.Transparent)),
        ) {
            video()
            // The track details and the scrubber sit over the lower half.
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)))
            }
        }
    }
}

/**
 * Where a Canvas goes on this screen.
 *
 * Blurred fills everywhere, since blurred the crop does not show. Full fills a portrait screen; on
 * a landscape one it takes the cover's place in Cinema's panel, or else runs down the middle,
 * blurred, with the background either side.
 */
internal fun canvasFraming(landscape: Boolean, mode: CanvasMode, cinemaPanel: Boolean): CanvasFraming = when {
    !landscape || mode == CanvasMode.BLURRED -> CanvasFraming.FILL
    cinemaPanel -> CanvasFraming.CARD
    else -> CanvasFraming.COLUMN
}

private const val PORTRAIT = 9f / 16f

/** Fades the content by [alpha] and by [shape]'s own alpha, drawn offscreen so the mask cuts it. */
private fun Modifier.mask(alpha: Float, shape: Brush): Modifier = this
    .graphicsLayer {
        this.alpha = alpha
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithContent {
        drawContent()
        drawRect(shape, blendMode = BlendMode.DstIn)
    }

/** One video's player and surface, owned by the composable that shows it. */
private class CanvasPlayback(private val file: File) : TextureView.SurfaceTextureListener {

    private val player = MediaPlayer()
    private var view: TextureView? = null
    private var surface: Surface? = null
    private var texture: SurfaceTexture? = null
    /** The player's surface after its view let it go, kept until the next one takes over. */
    private var orphan: SurfaceTexture? = null
    private var loading = false
    private var prepared = false
    private var shown = false
    private var released = false
    private var wantPlaying = false

    var onFirstFrame: () -> Unit = {}
    var onFailure: () -> Unit = {}

    init {
        player.setOnPreparedListener {
            prepared = true
            crop()
            // Started even when the song is paused: nothing reaches the screen until playback does,
            // so a video opened while paused would otherwise never appear. It pauses on its first
            // frame instead, as Spotify's own Canvas does.
            player.start()
        }
        player.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                shown = true
                if (!wantPlaying) runCatching { player.pause() }
                onFirstFrame()
            }
            false
        }
        player.setOnVideoSizeChangedListener { _, _, _ -> crop() }
        player.setOnErrorListener { _, _, _ ->
            fail()
            true
        }
    }

    fun attach(textureView: TextureView) {
        view = textureView
        textureView.surfaceTextureListener = this
        textureView.surfaceTexture?.let { onSurfaceTextureAvailable(it, textureView.width, textureView.height) }
    }

    fun setPlaying(playing: Boolean) {
        wantPlaying = playing
        // Before the first frame the player runs regardless; the frame handler applies this.
        if (released || !prepared || !shown) return
        runCatching {
            if (playing && !player.isPlaying) player.start()
            if (!playing && player.isPlaying) player.pause()
        }
    }

    /** A video that will not play shows nothing, so its player is let go at once. */
    private fun fail() {
        onFailure()
        release()
    }

    fun release() {
        if (released) return
        released = true
        runCatching { player.release() }
        surface?.release()
        surface = null
        texture = null
        orphan?.release()
        orphan = null
        view?.surfaceTextureListener = null
        view = null
    }

    /**
     * A surface to draw into: the first, or a later one for the same video.
     *
     * Later ones come when the view is replaced — the framing changes on rotating, or on entering
     * and leaving the floating window — or when the window comes back after being hidden. The
     * player carries on into the new one.
     */
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        val previous = surface
        val previousOrphan = orphan
        val created = Surface(texture)
        surface = created
        this.texture = texture
        orphan = null
        runCatching {
            // Straight from the old surface to the new: a player given none in between stops
            // drawing video, and does not start again when given another.
            player.setSurface(created)
            previous?.release()
            previousOrphan?.release()
            if (!loading) {
                loading = true
                player.setDataSource(file.path)
                player.isLooping = true
                // A Canvas has no sound track, and the song is playing elsewhere; never add to it.
                player.setVolume(0f, 0f)
                player.prepareAsync()
            } else if (prepared) {
                crop()
                // A paused player draws nothing into a new surface until asked to.
                if (!player.isPlaying) {
                    player.seekTo(player.currentPosition.toLong(), MediaPlayer.SEEK_CLOSEST)
                }
            }
        }.onFailure { fail() }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = crop()

    /**
     * Never the player: a view is torn down as its replacement arrives, and the video carries on
     * into the new one.
     *
     * The player's current surface is kept, not released, until the next arrives. A replaced
     * view's surface can go after its successor's has come, and that one is simply let go.
     */
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (released || texture !== this.texture) return true
        orphan = texture
        this.texture = null
        return false
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    /** Scale to cover the view, centred: a TextureView otherwise stretches the video to fit. */
    private fun crop() {
        val target = view ?: return
        if (released || !prepared) return
        val videoWidth = player.videoWidth.toFloat()
        val videoHeight = player.videoHeight.toFloat()
        val width = target.width.toFloat()
        val height = target.height.toFloat()
        if (videoWidth <= 0f || videoHeight <= 0f || width <= 0f || height <= 0f) return
        val scale = maxOf(width / videoWidth, height / videoHeight)
        val matrix = Matrix()
        matrix.setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f)
        target.setTransform(matrix)
    }
}
