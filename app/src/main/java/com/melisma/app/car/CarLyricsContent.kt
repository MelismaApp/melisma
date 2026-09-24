package com.melisma.app.car

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melisma.app.AppContainer
import com.melisma.app.lyrics.LyricsState
import com.melisma.app.media.PlaybackPosition
import com.melisma.app.media.TrackInfo
import com.melisma.app.settings.CanvasMode
import com.melisma.app.settings.MediaPanelSide
import com.melisma.app.settings.Settings
import com.melisma.app.settings.ViewMode
import com.melisma.app.ui.background.DynamicBackground
import com.melisma.app.ui.background.ArtworkColors
import com.melisma.app.ui.background.CanvasVideoBackground
import com.melisma.app.ui.components.rememberPlayheadMs
import com.melisma.app.ui.lyrics.LyricsView

/**
 * The lyrics screen, drawn on the car's own surface.
 *
 * This is the phone's renderer, not an imitation of it: [LyricsView] and [DynamicBackground] are the
 * same composables the phone draws, so the syllable fill, the letter emphasis, the depth blur and the
 * drifting background arrive for free and cannot drift out of step with the phone's version.
 *
 * **Nothing here is interactive, by construction.** A car host does not deliver touches to the
 * surface as ordinary events — it sends map gestures through `SurfaceCallback` instead — so a button
 * drawn here would be a button that does nothing. Every control therefore lives in the host's own
 * action strip, where the targets are big, the host places them, and the driver already knows where
 * they are. The same rule settles the seek: tapping a line to jump to it is a phone gesture, and its
 * absence here is the platform being right.
 *
 * **Every choice is the phone's.** View mode, background style, romanization, furigana, translation,
 * type size, sync offset — all read from the same [Settings] the phone screen reads, because a car
 * is the worst possible place to be toggling anything.
 */
@Composable
fun CarLyricsContent(container: AppContainer, insets: PaddingValues) {
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val snapshot by container.media.snapshot.collectAsStateWithLifecycle()
    val lyricsState by container.lyrics.state.collectAsStateWithLifecycle()
    val extras by container.extras.collectAsStateWithLifecycle()
    val searched by container.searchedArtwork.collectAsStateWithLifecycle()
    val saving by container.saving.collectAsStateWithLifecycle()
    val canvas by container.canvasVideo.collectAsStateWithLifecycle()

    val permitted by container.media.permissionGranted.collectAsStateWithLifecycle()

    val artwork = extras.cover ?: searched ?: snapshot.artwork
    val colors = remember(artwork) { ArtworkColors.from(artwork) }

    // The same decision the templated screen makes, from the same function, so the two car screens
    // cannot disagree about whether there is anything to show. Drawing straight from a Loaded state
    // instead let two cases through that it already had answers for: untimed lyrics, which arrive as
    // a document and would sit here as a frozen page nobody can scroll, and missing notification
    // access, which showed as "nothing playing" — true, and permanently so, with no hint why.
    //
    // Only the *kind* of answer is used; which line is being sung is the renderer's own business.
    val glance = CarGlance.of(
        state = lyricsState,
        hasTrack = snapshot.hasTrack,
        permissionGranted = permitted,
        positionMs = snapshot.playback.currentMs() + settings.syncOffsetMs,
        settings = settings,
    )
    val document = (lyricsState as? LyricsState.Loaded)?.document?.takeIf { glance is CarGlance.Now }

    // Blurred Canvas only. Full is a picture to watch, which a driver must not be given; blurred it is
    // colour in motion, no busier than Living. The blur needs Android 12, and unblurred is not an
    // acceptable fallback here.
    val video = canvas?.takeIf {
        settings.canvasMode == CanvasMode.BLURRED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (!saving.stillBackground || it.file == null) && it.trackId == snapshot.track?.spotifyTrackId
    }
    var videoShowing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        DynamicBackground(
            artwork = artwork,
            colors = colors,
            style = settings.backgroundStyle,
            blurRadius = settings.backgroundBlur,
            // A car screen is on for the whole drive with the phone's own switch already applied:
            // battery saver holds it still here for the same reason it does there.
            preferStill = saving.stillBackground,
            stillAsCover = saving.stillAsCover,
            playing = snapshot.playback.isPlaying && !videoShowing,
            artistImage = extras.artistImage,
            tempoBpm = extras.tempo,
        )

        if (video != null) {
            // The track, not the file: the still and then the video are one Canvas.
            key(video.trackId) {
                CanvasVideoBackground(
                    file = video.file,
                    poster = video.poster,
                    blurred = true,
                    playing = snapshot.playback.isPlaying,
                    onShowing = { videoShowing = it },
                )
            }
        }

        // Inside the host's own idea of what is visible: it draws a header and an action strip over
        // this surface and says where, so the words go in the gap rather than under the furniture.
        Box(Modifier.fillMaxSize().padding(insets)) {
            val track = snapshot.track
            when {
                document == null -> CarStatus(text = statusText(glance), accent = colors.accent)

                settings.viewMode == ViewMode.CINEMA && track != null -> CarCinema(
                    track = track,
                    playback = snapshot.playback,
                    artwork = artwork,
                    accent = colors.accent,
                    side = settings.mediaPanelSide,
                    lyrics = { modifier -> CarLyrics(container, document, settings, snapshot, modifier) },
                )

                else -> CarLyrics(container, document, settings, snapshot, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CarLyrics(
    container: AppContainer,
    document: com.melisma.app.lyrics.model.LyricsDocument,
    settings: Settings,
    snapshot: com.melisma.app.media.PlayerSnapshot,
    modifier: Modifier,
) {
    val playback = snapshot.playback
    val offset = settings.syncOffsetMs
    // The same sum the phone uses. Recreated only when playback or the offset changes, so the draw
    // phase reads a lambda that is never stale — the mistake that once froze the phone's own view.
    val position = remember(playback, offset) { { playback.currentMs() + offset } }

    LyricsView(
        document = document,
        settings = settings,
        positionMsProvider = position,
        // The host swallows touches on this surface, so nothing can be tapped to seek. Handing it a
        // real seek anyway would be a control that silently does nothing.
        onSeek = {},
        modifier = modifier,
    )
}

/**
 * Cinema view, minus the things a car cannot use.
 *
 * The phone's own panel is a scrubber and a row of buttons, and neither can work on a surface the
 * host does not send touches to. So this is the same shape — art on one side, words on the other,
 * with the side the phone was told to use — carrying only what can be read: the cover, the title, the
 * artist, and how far through it is.
 */
@Composable
private fun CarCinema(
    track: TrackInfo,
    playback: PlaybackPosition,
    artwork: Bitmap?,
    accent: Color,
    side: MediaPanelSide,
    lyrics: @Composable (Modifier) -> Unit,
) {
    val panel: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) {
                    Image(
                        bitmap = artwork.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = track.title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = track.artist.ifBlank { "Unknown artist" },
                color = Color.White.copy(alpha = 0.66f),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            CarProgress(playback = playback, accent = accent)
        }
    }

    Row(Modifier.fillMaxSize()) {
        if (side == MediaPanelSide.START) {
            panel(Modifier.weight(0.36f).fillMaxHeight())
            lyrics(Modifier.weight(0.64f).fillMaxHeight())
        } else {
            lyrics(Modifier.weight(0.64f).fillMaxHeight())
            panel(Modifier.weight(0.36f).fillMaxHeight())
        }
    }
}

/** How far through the song, as a line rather than a control: there is nothing to drag here. */
@Composable
private fun CarProgress(playback: PlaybackPosition, accent: Color) {
    val duration = playback.durationMs.coerceAtLeast(1L)
    // A ticker, because the assumption this had before was wrong: the lyrics redraw themselves in the
    // draw phase, which does not recompose a sibling, and a player that publishes its position only
    // on a transition would have left this bar frozen where the song started. The phone's own bars
    // share this helper — five updates a second, and only while a window of ours is on screen.
    val positionMs by rememberPlayheadMs(playback)
    val fraction = (positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.22f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
    }
}

@Composable
private fun CarStatus(text: String, accent: Color) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One line, because that is all a driver should be given to read.
 *
 * The templated screen says the same things at more length; this is the version for a surface that is
 * mostly background, where a paragraph would be unreadable anyway.
 */
private fun statusText(glance: CarGlance): String = when (glance) {
    CarGlance.Silent -> "Nothing playing"
    CarGlance.Looking -> "Looking for the words…"
    CarGlance.None -> "No lyrics for this one"
    CarGlance.Untimed -> "These lyrics have no timings"
    CarGlance.Offline -> "No connection"
    CarGlance.NoPermission -> "Notification access is off — grant it on your phone"
    is CarGlance.Failed -> "Something went wrong"
    // Reached only if the caller drew this instead of the lyrics, which it does not.
    is CarGlance.Now -> "Looking for the words…"
}

/** Turns the host's visible rectangle into padding, in the density the surface is drawn at. */
internal fun insetsFor(
    area: android.graphics.Rect,
    widthPx: Int,
    heightPx: Int,
    density: Float,
): PaddingValues {
    if (area.isEmpty || widthPx <= 0 || heightPx <= 0) return PaddingValues(0.dp)
    fun px(value: Int) = (value / density).dp
    return PaddingValues(
        start = px(area.left.coerceAtLeast(0)),
        top = px(area.top.coerceAtLeast(0)),
        end = px((widthPx - area.right).coerceAtLeast(0)),
        bottom = px((heightPx - area.bottom).coerceAtLeast(0)),
    )
}
