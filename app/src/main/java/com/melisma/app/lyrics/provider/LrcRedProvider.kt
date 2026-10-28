package com.melisma.app.lyrics.provider

import com.melisma.app.BuildConfig
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.parse.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * lrc.red — Apple Music's lyrics by ISRC, with no token.
 *
 * The same word-timed TTML the Apple Music source returns, official romanizations and translations
 * included, served keylessly and indexed by ISRC. With the recording identified — which the app now
 * does for most players — this is one exact request; its title search is the fallback, and it has
 * little outside Western and Korean catalogues, so the search is not where this source earns its
 * place.
 *
 * Their terms: a User-Agent naming the app with a link and a way to get in touch, and a credit that
 * links back wherever the lyrics are shown. The source line under the last line is that credit.
 *
 * https://lrc.red/api
 */
class LrcRedProvider(private val base: String = BASE) : LyricsProvider {

    override val id = "lrcred"
    override val displayName = "lrc.red"
    override val canBeWordSynced = true
    override val link = BASE
    override val creditRequired = true

    @Serializable
    private data class Search(val hits: List<Hit> = emptyList())

    @Serializable
    private data class Hit(
        val isrc: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val duration: Double = 0.0,
    ) {
        val durationMs: Long get() = (duration * 1000).toLong()
    }

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? = withContext(Dispatchers.IO) {
        request.isrc?.takeIf { it.isNotBlank() }?.let { isrc -> byIsrc(isrc)?.let { return@withContext it } }
        search(request)
    }

    private suspend fun byIsrc(isrc: String): LyricsDocument? =
        Http.get("$base/s/${Http.encode(isrc.trim().uppercase())}.ttml", headers) { body ->
            TtmlParser.parse(appleTtml(body), displayName, id)
        }

    private suspend fun search(request: LyricsRequest): LyricsDocument? {
        if (request.title.isBlank()) return null
        val query = "${request.title} ${request.primaryArtist}".trim()
        val hits = Http.get("$base/search.json?q=${Http.encode(query)}", headers) { body ->
            runCatching { Http.json.decodeFromString<Search>(body) }.getOrNull()?.hits
        }.orEmpty()
        val best = bestHit(hits, request) ?: return null
        // The identity is worth keeping whether or not the lyrics turn out to exist.
        request.onIsrc?.invoke(best.isrc)
        return byIsrc(best.isrc)
    }

    private fun bestHit(hits: List<Hit>, request: LyricsRequest): Hit? = hits
        .filter { it.isrc.isNotBlank() }
        .map { it to Matching.score(request, it.title, it.artist, it.durationMs) }
        .filter { (_, score) -> score >= Matching.MATCH_THRESHOLD }
        .maxByOrNull { it.second }?.first

    private val headers = mapOf(
        // Their terms ask for the app, a link and a way to get in touch.
        "User-Agent" to "Melisma/${BuildConfig.VERSION_NAME} (Android; https://github.com/MelismaApp/melisma; " +
            "contact https://github.com/MelismaApp/melisma/issues)",
        "Accept" to "application/ttml+xml, application/xml, application/json, text/plain",
    )

    companion object {
        const val BASE = "https://lrc.red"

        private val LRC_ATTRIBUTE = Regex("""(?<=\s)lrc:(?=[A-Za-z]+=)""")

        /**
         * lrc.red writes Apple's TTML under its own namespace — `lrc:timing` for `itunes:timing`,
         * `sourceMetadata` for `iTunesMetadata` — and the parser knows Apple's names.
         */
        fun appleTtml(ttml: String): String = ttml
            .replace("xmlns:lrc=\"http://lrc.red/lyric-ttml-internal\"", "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\"")
            .replace("xmlns=\"http://lrc.red/lyric-ttml-internal\"", "xmlns=\"http://music.apple.com/lyric-ttml-internal\"")
            .replace(LRC_ATTRIBUTE, "itunes:")
    }
}
