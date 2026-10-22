package com.melisma.app.lyrics.identity

import com.melisma.app.lyrics.provider.Http
import com.melisma.app.lyrics.provider.LyricsRequest
import com.melisma.app.media.PlayerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs

/**
 * Turns the id a player gave a track into the recording's ISRC, where a catalogue can be asked
 * without a key.
 *
 * Deezer's public API answers for a track id with no credential, so a Deezer track is identified
 * exactly before any source is asked. Other players' ids go to the cache server, which holds the
 * credentials those catalogues want.
 */
class TrackIdentifier {

    suspend fun isrcFor(request: LyricsRequest): String? = withContext(Dispatchers.IO) {
        val playerId = request.playerId?.let(PlayerId::parse) ?: return@withContext null
        when (playerId.service) {
            PlayerId.DEEZER -> Http.get("$DEEZER_API/track/${Http.encode(playerId.id)}") { body ->
                deezerIsrc(body, request.durationMs)
            }

            else -> null
        }
    }

    companion object {
        private const val DEEZER_API = "https://api.deezer.com"

        /**
         * Players round a length differently from catalogues, and Deezer gives whole seconds, so a
         * couple of seconds either way is the same recording; more than that is a different one
         * under the same id, which happens when a track is replaced.
         */
        const val DURATION_TOLERANCE_MS = 3_000L

        /** The ISRC in a Deezer track response, if it describes a track of [durationMs]. */
        fun deezerIsrc(body: String, durationMs: Long): String? = runCatching {
            val track = Json.parseToJsonElement(body).jsonObject
            val isrc = track["isrc"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
                ?.takeIf { it.length == 12 } ?: return null
            val seconds = track["duration"]?.jsonPrimitive?.longOrNull
            if (durationMs > 0 && seconds != null && seconds > 0 &&
                abs(seconds * 1000 - durationMs) > DURATION_TOLERANCE_MS
            ) {
                return null
            }
            isrc
        }.getOrNull()
    }
}
