package com.melisma.app.lyrics.identity

import com.melisma.app.lyrics.provider.Http
import com.melisma.app.lyrics.provider.LyricsRequest
import com.melisma.app.lyrics.provider.Matching
import com.melisma.app.lyrics.provider.cleanTrackTitle
import com.melisma.app.media.PlayerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs

/**
 * Finds the recording's ISRC for a track, from catalogues that can be asked without a key.
 *
 * Two ways, worth different amounts. [isrcFor] turns the id a player gave the track into an ISRC:
 * exact, so it is asked before any source is. [searchIsrc] looks the names up in a catalogue the
 * way a source would, and is held to a stricter match than a source is, because its answer is
 * remembered for every later play.
 */
class TrackIdentifier {

    /** The ISRC behind the player's own id, where a catalogue answers for one without a key. */
    suspend fun isrcFor(request: LyricsRequest): String? = withContext(Dispatchers.IO) {
        val playerId = request.playerId?.let(PlayerId::parse) ?: return@withContext null
        when (playerId.service) {
            PlayerId.DEEZER -> deezerTrack(playerId.id, request.durationMs)
            else -> null
        }
    }

    /**
     * The ISRC of the recording the names and length describe, or null when nothing describes them
     * closely enough to be sure.
     *
     * MusicBrainz first, because its search results carry ISRCs; Deezer second, where a hit costs
     * one more request for the ISRC. Both are asked by name, so both are held to [acceptable].
     */
    suspend fun searchIsrc(request: LyricsRequest): String? = withContext(Dispatchers.IO) {
        if (request.title.isBlank()) return@withContext null
        musicBrainz(request) ?: deezerSearch(request)
    }

    private suspend fun musicBrainz(request: LyricsRequest): String? {
        val query = buildList {
            add("recording:\"${request.title.replace("\"", "")}\"")
            request.primaryArtist.takeIf { it.isNotBlank() }?.let { add("artist:\"${it.replace("\"", "")}\"") }
        }.joinToString(" AND ")
        val url = "$MUSICBRAINZ_API/recording?query=${Http.encode(query)}&fmt=json&limit=$SEARCH_LIMIT"
        return Http.get(url, ACCEPT_JSON) { body -> musicBrainzIsrc(body, request) }
    }

    private suspend fun deezerSearch(request: LyricsRequest): String? {
        val term = "${request.primaryArtist} ${request.title}".trim()
        val id = Http.get("$DEEZER_API/search?q=${Http.encode(term)}&limit=$SEARCH_LIMIT", ACCEPT_JSON) { body ->
            deezerSearchId(body, request)
        } ?: return null
        return deezerTrack(id, request.durationMs)
    }

    private suspend fun deezerTrack(id: String, durationMs: Long): String? =
        Http.get("$DEEZER_API/track/${Http.encode(id)}", ACCEPT_JSON) { body -> deezerIsrc(body, durationMs) }

    companion object {
        private const val DEEZER_API = "https://api.deezer.com"
        private const val MUSICBRAINZ_API = "https://musicbrainz.org/ws/2"
        private val ACCEPT_JSON = mapOf("Accept" to "application/json")
        private const val SEARCH_LIMIT = 5

        /**
         * Players round a length differently from catalogues, and Deezer gives whole seconds, so a
         * couple of seconds either way is the same recording; more than that is a different one
         * under the same id, which happens when a track is replaced.
         */
        const val DURATION_TOLERANCE_MS = 3_000L

        /**
         * What a search result must score to be remembered as this track's recording. Higher than a
         * lyrics source needs, because a source's wrong answer lasts until the cache expires and this
         * one lasts as long as the app is installed.
         */
        const val SEARCH_THRESHOLD = 0.8f

        /** How close the lengths must be when the artist's name could not be compared at all. */
        const val EXACT_LENGTH_MS = 1_000L

        /** The ISRC in a Deezer track response, if it describes a track of [durationMs]. */
        fun deezerIsrc(body: String, durationMs: Long): String? = runCatching {
            val track = Json.parseToJsonElement(body).jsonObject
            val isrc = isrcOf(track["isrc"]?.jsonPrimitive?.contentOrNull) ?: return null
            val seconds = track["duration"]?.jsonPrimitive?.longOrNull
            if (!sameLength(durationMs, seconds?.times(1000))) return null
            isrc
        }.getOrNull()

        /** The Deezer id of the result that describes [request], from a search response. */
        fun deezerSearchId(body: String, request: LyricsRequest): String? = runCatching {
            Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
                .mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }
                .mapNotNull { track ->
                    val id = track["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val title = track["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val artist = track["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val lengthMs = track["duration"]?.jsonPrimitive?.longOrNull?.times(1000)
                    acceptable(request, title, listOfNotNull(artist), lengthMs)?.let { score -> id to score }
                }
                .maxByOrNull { it.second }?.first
        }.getOrNull()

        /** The ISRC of the recording in a MusicBrainz search response that describes [request]. */
        fun musicBrainzIsrc(body: String, request: LyricsRequest): String? = runCatching {
            Json.parseToJsonElement(body).jsonObject["recordings"]?.jsonArray.orEmpty()
                .mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }
                .mapNotNull { recording ->
                    val isrc = recording["isrcs"]?.jsonArray.orEmpty()
                        .firstNotNullOfOrNull { isrcOf(it.jsonPrimitive.contentOrNull) }
                        ?: return@mapNotNull null
                    val title = recording["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    // Every spelling MusicBrainz has for the artist, so a name the player wrote in
                    // Latin letters can be compared with one the database keeps in Japanese.
                    val artists = recording["artist-credit"]?.jsonArray.orEmpty()
                        .flatMap { credit -> runCatching { credit.jsonObject.artistNames() }.getOrDefault(emptyList()) }
                    val lengthMs = recording["length"]?.jsonPrimitive?.longOrNull
                    acceptable(request, title, artists, lengthMs)?.let { score -> isrc to score }
                }
                .maxByOrNull { it.second }?.first
        }.getOrNull()

        private fun JsonObject.artistNames(): List<String> {
            val artist = get("artist")?.jsonObject
            return listOfNotNull(
                get("name")?.jsonPrimitive?.contentOrNull,
                artist?.get("name")?.jsonPrimitive?.contentOrNull,
                artist?.get("sort-name")?.jsonPrimitive?.contentOrNull,
            ) + artist?.get("aliases")?.jsonArray.orEmpty()
                .mapNotNull { alias -> runCatching { alias.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        }

        /**
         * The match score when a candidate may be taken for [request], else null.
         *
         * The length has to be known on both sides and agree: a name match alone is how a live
         * version or a remix is mistaken for the recording. And when no spelling of the artist can
         * be compared with the player's — a Latin name against a Japanese one — the title has to be
         * the same and the length all but exact, because the artist then counts for nothing.
         */
        private fun acceptable(request: LyricsRequest, title: String, artists: List<String>, lengthMs: Long?): Float? {
            if (request.durationMs <= 0 || lengthMs == null || lengthMs <= 0) return null
            if (!sameLength(request.durationMs, lengthMs)) return null
            val score = Matching.score(request, title, artists.joinToString(", "), lengthMs)
            if (score < SEARCH_THRESHOLD) return null
            val artistWeighed = request.artist.isBlank() || artists.isEmpty() ||
                artists.any { Matching.comparableScripts(request.artist, it) }
            if (!artistWeighed) {
                val sameTitle = cleanTrackTitle(title).equals(request.cleanTitle, ignoreCase = true)
                if (!sameTitle || abs(lengthMs - request.durationMs) > EXACT_LENGTH_MS) return null
            }
            return score
        }

        private fun sameLength(durationMs: Long, lengthMs: Long?): Boolean =
            durationMs <= 0 || lengthMs == null || lengthMs <= 0 ||
                abs(lengthMs - durationMs) <= DURATION_TOLERANCE_MS

        private fun isrcOf(raw: String?): String? =
            raw?.trim()?.uppercase()?.replace("-", "")?.takeIf { it.length == 12 }
    }
}
