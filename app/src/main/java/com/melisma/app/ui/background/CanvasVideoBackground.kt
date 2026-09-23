package com.melisma.app.ui.background

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/**
 * A Spotify Canvas video as the background: muted, looping, cropped to fill.
 *
 * Fades in on its first decoded frame, over whatever background is beneath it, so a slow load or a
 * file that will not play simply leaves that background showing. [onShowing] reports when it is
 * covering the screen, so the background underneath can stop animating.
 *
 * [blurred] uses a render effect, which Android 12 introduced; before that it plays unblurred.
 */
@Composable
fun CanvasVideoBackground(
    file: File,
    blurred: Boolean,
    playing: Boolean,
    onShowing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playback = remember(file) { CanvasPlayback(file) }
    val reportShowing by rememberUpdatedState(onShowing)
    var showing by remember(file) { mutableStateOf(false) }
    playback.onFirstFrame = { showing = true; reportShowing(true) }
    playback.onFailure = { showing = false; reportShowing(false) }

    DisposableEffect(playback) {
        onDispose {
            playback.release()
            reportShowing(false)
        }
    }

    // Paused music pauses the video; so does the screen going away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val wantsToPlay by rememberUpdatedState(playing)
    DisposableEffect(lifecycle, playback) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> playback.setPlaying(false)
                Lifecycle.Event.ON_START -> playback.setPlaying(wantsToPlay)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playback, playing) { playback.setPlaying(playing) }

    val alpha by animateFloatAsState(if (showing) 1f else 0f, tween(durationMillis = 600), label = "canvas")

    Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        AndroidView(
            factory = { context -> TextureView(context).apply { playback.attach(this) } },
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurred) Modifier.blur(28.dp, BlurredEdgeTreatment.Rectangle) else Modifier),
        )
        // Spicy Lyrics dims a Canvas to brightness(0.65); the lyrics need the same headroom here.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.35f))
            drawFloorShade()
        }
    }
}

/** One video's player and surface, owned by the composable that shows it. */
private class CanvasPlayback(private val file: File) : TextureView.SurfaceTextureListener {

    private val player = MediaPlayer()
    private var view: TextureView? = null
    private var surface: Surface? = null
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
            onFailure()
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

    fun release() {
        if (released) return
        released = true
        runCatching { player.release() }
        surface?.release()
        surface = null
        view?.surfaceTextureListener = null
        view = null
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released || surface != null) return
        val created = Surface(texture)
        surface = created
        runCatching {
            player.setSurface(created)
            player.setDataSource(file.path)
            player.isLooping = true
            // A Canvas has no sound track, and the song is playing elsewhere; never add to it.
            player.setVolume(0f, 0f)
            player.prepareAsync()
        }.onFailure { onFailure() }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = crop()

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
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
