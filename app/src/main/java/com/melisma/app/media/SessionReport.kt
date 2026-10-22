package com.melisma.app.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle

/**
 * Everything one media session publishes, for *Developer → What players publish*.
 *
 * What a player puts in its session decides how exactly a track can be matched — Spotify publishes
 * `spotify:track:…` as its media id, most players publish nothing beyond a title and an artist — and
 * there is no way to know which without looking. This is the looking: every metadata key, the
 * session's extras and the queue around the playing item, as text a user can copy into a report.
 */
internal data class SessionReport(
    val packageName: String,
    val label: String,
    /** Whether the lyrics follow this session, and if not, why not. */
    val status: String,
    val sections: List<Pair<String, List<Pair<String, String>>>>,
)

internal fun formatSessionReports(header: List<Pair<String, String>>, sessions: List<SessionReport>): String =
    buildString {
        for ((key, value) in header) append(key).append(": ").append(value).append('\n')
        if (sessions.isEmpty()) {
            append("\nNo media sessions.\n")
            return@buildString
        }
        for ((index, session) in sessions.withIndex()) {
            append("\n== ").append(index + 1).append(". ").append(session.label)
                .append(" (").append(session.packageName).append(")\n")
            append(session.status).append('\n')
            for ((title, lines) in session.sections) {
                append("-- ").append(title).append('\n')
                if (lines.isEmpty()) append("  (none)\n")
                for ((key, value) in lines) append("  ").append(key).append(" = ").append(value).append('\n')
            }
        }
    }

/** [MediaController]'s session as a [SessionReport]. */
internal fun MediaController.report(
    label: String,
    status: String,
    read: TrackInfo?,
    nowElapsed: Long,
): SessionReport {
    val state = runCatching { playbackState }.getOrNull()
    val sections = buildList {
        add("What Melisma reads" to read?.let(::readLines).orEmpty())
        add("Playback state" to playbackLines(state, nowElapsed))
        add("Metadata" to metadataLines(runCatching { metadata }.getOrNull()))
        add("Session extras" to bundleLines(runCatching { extras }.getOrNull()))
        add(
            "Queue" to queueLines(
                runCatching { queue }.getOrNull(),
                runCatching { queueTitle }.getOrNull(),
                state?.activeQueueItemId,
            ),
        )
    }
    return SessionReport(packageName, label, status, sections)
}

private fun readLines(track: TrackInfo): List<Pair<String, String>> = listOf(
    "title" to quoted(track.title),
    "artist" to quoted(track.artist),
    "album" to quoted(track.album),
    "durationMs" to track.durationMs.toString(),
    "mediaId" to (track.mediaId?.let(::quoted) ?: "none"),
    "playerId" to (track.playerId?.toString() ?: "none"),
    "coverUrl" to (track.coverUrl?.let(::quoted) ?: "none"),
    "spotifyTrackId" to (track.spotifyTrackId ?: "none"),
    "cacheKey" to quoted(track.cacheKey),
)

private fun playbackLines(state: PlaybackState?, nowElapsed: Long): List<Pair<String, String>> {
    state ?: return emptyList()
    return buildList {
        add("state" to stateName(state.state))
        add(
            "position" to if (state.lastPositionUpdateTime > 0) {
                "${state.position} ms, reported ${(nowElapsed - state.lastPositionUpdateTime).coerceAtLeast(0)} ms ago"
            } else {
                "${state.position} ms"
            },
        )
        add("speed" to state.playbackSpeed.toString())
        add("bufferedPosition" to state.bufferedPosition.toString())
        add("actions" to actionNames(state.actions))
        add("activeQueueItemId" to state.activeQueueItemId.toString())
        state.errorMessage?.let { add("errorMessage" to quoted(it.toString())) }
        state.customActions.takeIf { it.isNotEmpty() }?.let { actions ->
            add("customActions" to actions.joinToString { it.action })
        }
        for ((key, value) in bundleLines(state.extras)) add("extras.$key" to value)
    }
}

/**
 * Every key, in order. A session does not say which type each key holds, so each getter is tried in
 * turn; a wrong guess returns null rather than throwing.
 */
private fun metadataLines(metadata: MediaMetadata?): List<Pair<String, String>> {
    metadata ?: return emptyList()
    return metadata.keySet().sorted().map { key ->
        key to (
            metadata.getText(key)?.let { quoted(it.toString()) }
                ?: metadata.getBitmap(key)?.let(::bitmapSize)
                ?: metadata.getRating(key)?.toString()
                ?: metadata.getLong(key).toString()
            )
    }
}

internal fun bundleLines(bundle: Bundle?): List<Pair<String, String>> {
    bundle ?: return emptyList()
    // Reading a key unparcels the whole bundle, and a player's own Parcelable classes are not on
    // this app's class path.
    return runCatching {
        bundle.keySet().sorted().map { key ->
            @Suppress("DEPRECATION")
            key to describe(bundle.get(key))
        }
    }.getOrElse { listOf("unreadable" to it.javaClass.simpleName) }
}

private fun describe(value: Any?): String = when (value) {
    null -> "null"
    is CharSequence -> quoted(value.toString())
    is Bitmap -> bitmapSize(value)
    is Bundle -> bundleLines(value).joinToString(prefix = "{", postfix = "}") { (k, v) -> "$k=$v" }
    is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { describe(it) }
    is IntArray -> value.contentToString()
    is LongArray -> value.contentToString()
    else -> value.toString()
}

/** The playing item and the few around it, or the start of the queue when none is marked playing. */
internal fun queueLines(
    queue: List<MediaSession.QueueItem>?,
    queueTitle: CharSequence?,
    activeId: Long?,
): List<Pair<String, String>> {
    queue ?: return emptyList()
    val active = queue.indexOfFirst { it.queueId == activeId }
    val from = if (active < 0) 0 else (active - 1).coerceAtLeast(0)
    return buildList {
        queueTitle?.let { add("title" to quoted(it.toString())) }
        add("size" to queue.size.toString())
        for (index in from until (from + QUEUE_SHOWN).coerceAtMost(queue.size)) {
            val item = queue[index]
            val description = item.description
            val marker = if (index == active) " (playing)" else ""
            val fields = buildList {
                add("mediaId=${description.mediaId?.let(::quoted) ?: "none"}")
                add("title=${quoted(description.title?.toString().orEmpty())}")
                add("subtitle=${quoted(description.subtitle?.toString().orEmpty())}")
                description.mediaUri?.let { add("mediaUri=${quoted(it.toString())}") }
                bundleLines(description.extras).forEach { (key, value) -> add("$key=$value") }
            }
            add("#${item.queueId}$marker" to fields.joinToString(", "))
        }
    }
}

private fun quoted(text: String) = "\"$text\""

private fun bitmapSize(bitmap: Bitmap) = "bitmap ${bitmap.width}×${bitmap.height}"

private fun stateName(state: Int): String = when (state) {
    PlaybackState.STATE_NONE -> "NONE"
    PlaybackState.STATE_STOPPED -> "STOPPED"
    PlaybackState.STATE_PAUSED -> "PAUSED"
    PlaybackState.STATE_PLAYING -> "PLAYING"
    PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
    PlaybackState.STATE_REWINDING -> "REWINDING"
    PlaybackState.STATE_BUFFERING -> "BUFFERING"
    PlaybackState.STATE_ERROR -> "ERROR"
    PlaybackState.STATE_CONNECTING -> "CONNECTING"
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
    PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
    else -> state.toString()
}

private val ACTIONS = listOf(
    PlaybackState.ACTION_STOP to "STOP",
    PlaybackState.ACTION_PAUSE to "PAUSE",
    PlaybackState.ACTION_PLAY to "PLAY",
    PlaybackState.ACTION_REWIND to "REWIND",
    PlaybackState.ACTION_SKIP_TO_PREVIOUS to "SKIP_TO_PREVIOUS",
    PlaybackState.ACTION_SKIP_TO_NEXT to "SKIP_TO_NEXT",
    PlaybackState.ACTION_FAST_FORWARD to "FAST_FORWARD",
    PlaybackState.ACTION_SET_RATING to "SET_RATING",
    PlaybackState.ACTION_SEEK_TO to "SEEK_TO",
    PlaybackState.ACTION_PLAY_PAUSE to "PLAY_PAUSE",
    PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "PLAY_FROM_MEDIA_ID",
    PlaybackState.ACTION_PLAY_FROM_SEARCH to "PLAY_FROM_SEARCH",
    PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "SKIP_TO_QUEUE_ITEM",
    PlaybackState.ACTION_PLAY_FROM_URI to "PLAY_FROM_URI",
    PlaybackState.ACTION_PREPARE to "PREPARE",
    PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID to "PREPARE_FROM_MEDIA_ID",
    PlaybackState.ACTION_PREPARE_FROM_SEARCH to "PREPARE_FROM_SEARCH",
    PlaybackState.ACTION_PREPARE_FROM_URI to "PREPARE_FROM_URI",
    PlaybackState.ACTION_SET_PLAYBACK_SPEED to "SET_PLAYBACK_SPEED",
)

/** Each flag by name, and any bit newer than this list as a number, so nothing is dropped. */
internal fun actionNames(actions: Long): String {
    val named = ACTIONS.filter { (flag, _) -> actions and flag != 0L }.map { it.second }
    val unknown = actions and ACTIONS.fold(0L) { mask, (flag, _) -> mask or flag }.inv()
    val parts = if (unknown != 0L) named + "0x${unknown.toString(16)}" else named
    return parts.joinToString(" ").ifEmpty { "none" }
}

private const val QUEUE_SHOWN = 5

/**
 * Whether the lyrics follow a session, and if not, the rule in [chooseSession] that passed it over.
 * [lastPlayingAgoMs] is the session's app's, shared by every session that app publishes.
 */
internal fun sessionStatus(
    own: Boolean,
    followed: Boolean,
    ignored: Boolean,
    hasTitle: Boolean,
    playing: Boolean,
    lastPlayingAgoMs: Long?,
): String {
    val reason = when {
        own -> "Melisma's own session, never followed"
        followed -> "FOLLOWED by the lyrics"
        ignored -> "Not followed: switched off under Players to follow"
        !hasTitle -> "Not followed: publishes no title"
        playing -> "Not followed: another playing session reported playing more recently"
        else -> "Not followed: not playing"
    }
    // Playing history is kept per app, not per session, so it is said of the app.
    val last = lastPlayingAgoMs?.let { "its app last reported playing ${it / 1000} s ago" }
        ?: "its app not seen playing"
    return "$reason; $last"
}
