package com.melisma.app.media

import android.graphics.Bitmap
import android.os.SystemClock

data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** `spotify:track:…` style id when the player exposes one; used for exact lookups. */
    val mediaId: String? = null,
    val artworkUri: String? = null,
    val packageName: String = "",
    /** An https address of the full-size cover, when the player published one. See [coverUrlOf]. */
    val coverUrl: String? = null,
) {
    val spotifyTrackId: String?
        get() = mediaId?.let { id ->
            when {
                id.startsWith("spotify:track:") -> id.removePrefix("spotify:track:")
                    .takeIf { it.length == 22 }
                else -> null
            }
        }

    /**
     * The player's own id for the track, when the player is one whose ids are understood. Spotify's
     * is [spotifyTrackId] instead. Read with the package, because a bare number is only a Tidal id
     * when Tidal published it.
     */
    val playerId: PlayerId?
        get() = PlayerId.of(packageName, mediaId)

    /** Stable key for the lyrics cache. Deliberately excludes artwork and app. */
    val cacheKey: String
        get() = spotifyTrackId?.let { "sp:$it" }
            ?: buildString {
                append(title.lowercase().trim())
                append('|')
                append(artist.lowercase().trim())
                append('|')
                // Bucket the duration so a one-second reporting difference between
                // players doesn't split the cache entry in two.
                append(durationMs / 2000)
            }

    val isEmpty: Boolean get() = title.isBlank() && artist.isBlank()

    /**
     * Whether this track — read from a player's queue rather than from what is playing —
     * can be looked up now and found again later under the same [cacheKey].
     *
     * A queue entry publishes far less than the metadata of a playing track: usually a
     * title and an artist, and no duration. `title|artist|0` is not the key the same track
     * gets once it starts and its real duration is known, so prefetching it would spend
     * the request and never be read back. A Spotify id or a duration makes the two keys
     * agree; without either, the honest answer is not to bother.
     */
    val isPrefetchable: Boolean
        get() = title.isNotBlank() && (spotifyTrackId != null || durationMs > 0)
}

/**
 * The full-size cover a player publishes beside its thumbnail, if any.
 *
 * Spotify sends its 640 px cover under its own key, which is how a phone with no Spotify token
 * still gets it; other players' `ALBUM_ART_URI` is sometimes an https image of a usable size.
 * A `content://` address is not one: it is read for the thumbnail already.
 */
fun coverUrlOf(spotifyArtUrl: String?, artworkUri: String?): String? =
    listOfNotNull(spotifyArtUrl, artworkUri).map { it.trim() }.firstOrNull { it.startsWith("https://") }

/**
 * A track id as a player published it: `deezer:2981739661`, `tidal:134858527`, `amazon:B0DX1H5KT5`.
 *
 * What each player writes in `METADATA_KEY_MEDIA_ID` was read off real sessions: Deezer puts the
 * queue index in front of the track id (`0.2981739661`), Tidal the bare id, Amazon Music the ASIN,
 * SoundCloud a `soundcloud:tracks:` URI. Spotify's `spotify:track:` id is not one of these; it has
 * its own field everywhere, because the sources take it directly.
 */
data class PlayerId(val service: String, val id: String) {
    override fun toString(): String = "$service:$id"

    companion object {
        const val DEEZER = "deezer"
        const val TIDAL = "tidal"
        const val AMAZON = "amazon"
        const val SOUNDCLOUD = "soundcloud"

        private val DEEZER_ID = Regex("""\d+\.(\d{5,})""")
        private val TIDAL_ID = Regex("""\d{4,}""")
        private val ASIN = Regex("""B0[A-Z0-9]{8}""")
        private val SOUNDCLOUD_ID = Regex("""soundcloud:tracks:(\d+)""")

        fun of(packageName: String, mediaId: String?): PlayerId? {
            val raw = mediaId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when (packageName) {
                "deezer.android.app" -> DEEZER_ID.matchEntire(raw)?.let { PlayerId(DEEZER, it.groupValues[1]) }
                "com.aspiro.tidal" -> TIDAL_ID.matchEntire(raw)?.let { PlayerId(TIDAL, raw) }
                "com.amazon.mp3" -> ASIN.matchEntire(raw)?.let { PlayerId(AMAZON, raw) }
                "com.soundcloud.android" ->
                    SOUNDCLOUD_ID.matchEntire(raw)?.let { PlayerId(SOUNDCLOUD, it.groupValues[1]) }

                else -> null
            }
        }

        /** The other way round, for an id that travelled as text. */
        fun parse(text: String): PlayerId? {
            val colon = text.indexOf(':')
            if (colon <= 0 || colon == text.lastIndex) return null
            return PlayerId(text.substring(0, colon), text.substring(colon + 1))
        }
    }
}

data class PlaybackPosition(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    /** [SystemClock.elapsedRealtime] when [positionMs] was reported. */
    val atElapsedRealtime: Long = 0L,
    val speed: Float = 1f,
    val durationMs: Long = 0L,
) {
    /**
     * Where the playhead is *now*. Media sessions only publish a position when
     * something changes, so between updates we extrapolate from the wall clock —
     * exactly what every "synced lyrics" implementation has to do.
     */
    fun currentMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying) return positionMs.coerceAtLeast(0L)
        val drift = ((nowElapsed - atElapsedRealtime) * speed).toLong()
        val projected = positionMs + drift
        return if (durationMs > 0) projected.coerceIn(0L, durationMs) else projected.coerceAtLeast(0L)
    }

    companion object {
        val IDLE = PlaybackPosition()
    }
}

/**
 * Which transport operations the player actually supports.
 *
 * A session that publishes a playback state has not thereby promised to accept every
 * command: a live stream cannot be sought or rewound, and a restricted session may only
 * offer play and pause. It says which in `PlaybackState.actions`, and a control that is
 * shown but silently ignored is worse than one that is visibly unavailable.
 */
data class Transport(
    val playPause: Boolean = false,
    val skipNext: Boolean = false,
    val skipPrevious: Boolean = false,
    val seek: Boolean = false,
) {
    val any: Boolean get() = playPause || skipNext || skipPrevious || seek
}

data class PlayerSnapshot(
    val track: TrackInfo? = null,
    val playback: PlaybackPosition = PlaybackPosition.IDLE,
    val artwork: Bitmap? = null,
    val sourcePackage: String? = null,
    val sourceLabel: String? = null,
    val transport: Transport = Transport(),
    /**
     * The track queued after this one, when the player publishes a queue at all.
     *
     * Only used to warm the lyrics cache ahead of time. Most players publish nothing
     * here — Spotify among them — so anything depending on it must treat null as the
     * normal case.
     */
    val nextTrack: TrackInfo? = null,
) {
    val hasTrack: Boolean get() = track != null && !track.isEmpty

    /** True when the player accepts at least one command, so a control row is worth showing. */
    val canControl: Boolean get() = transport.any
}
