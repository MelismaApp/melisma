package com.melisma.app.lyrics.provider

import com.melisma.app.lyrics.SpokenLanguageStore
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.parse.LrcParser
import com.melisma.app.lyrics.parse.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Your own caching server, if you are running one.
 *
 * A developer setting rather than a feature: the point of a server in front of the free
 * sources is that it answers once and remembers, so an endpoint run by volunteers is asked
 * one time per song instead of once per listener per play. Pointing the app at one is how
 * that cache gets filled — every track you play is a track the server has now seen.
 *
 * Deliberately permissive about what comes back, because the server is being written
 * alongside this and its exact shape is not settled. Anything recognisable is accepted:
 * TTML, LRC, or a JSON envelope carrying either. See `docs/CACHE-SERVER.md` for the
 * request contract this sends.
 */
class CacheServerProvider(
    private val credentials: ProviderCredentials,
    /** Where the language the server reports for a track is kept. */
    private val languages: SpokenLanguageStore? = null,
) : LyricsProvider {

    override val id = "cacheserver"
    override val displayName = "Cache server"

    /** Whatever it proxies may well be word-synced, so it must not be ranked below one. */
    override val canBeWordSynced = true

    override val isConfigured: Boolean
        get() = !credentials.cacheServerUrl.isNullOrBlank()

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? =
        withContext(Dispatchers.IO) {
            val base = credentials.cacheServerUrl?.trim()?.trimEnd('/')?.takeIf {
                it.isNotEmpty()
            } ?: return@withContext null

            val query = buildString {
                append("title=").append(Http.encode(request.title))
                append("&artist=").append(Http.encode(request.artist))
                if (request.album.isNotBlank()) {
                    append("&album=").append(Http.encode(request.album))
                }
                if (request.durationMs > 0) append("&durationMs=").append(request.durationMs)
                request.spotifyTrackId?.let { append("&spotifyId=").append(Http.encode(it)) }
            }

            // runCatching around the whole parse, not just the JSON: this is a server the
            // user is in the middle of writing, and a half-finished response should read as
            // "nothing here" rather than taking the whole lookup down with it — Http.get no
            // longer swallows what a parser throws.
            // On every answer, a miss included: a track tagged Hokkien with no lyrics held is still
            // Hokkien, and the words may come from another source.
            val onHeaders = { headers: okhttp3.Headers ->
                headers[LANGUAGE_HEADER]?.trim()?.lowercase()?.takeIf { it in LANGUAGES }
                    ?.let { languages?.put(request.cacheIdentity(), it) }
                Unit
            }
            Http.get("$base/v1/lyrics?$query", headers(), onHeaders = onHeaders) { body ->
                runCatching { documentFrom(body) }.getOrNull()
            }
        }

    /** What became of telling the server which language a track is in. */
    enum class LanguageTag { SAVED, NO_SERVER, NO_KEY, WRONG_KEY, NOT_ADMIN, FAILED }

    /**
     * Tell the server which language [request]'s track is sung in, or with null that it should decide
     * for itself again.
     *
     * The one thing the app ever writes to the server, and only with the admin key: see
     * `docs/CACHE-SERVER.md`. Sent once per choice, never retried.
     */
    suspend fun putLanguage(request: LyricsRequest, language: String?): LanguageTag = withContext(Dispatchers.IO) {
        val base = credentials.cacheServerUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: return@withContext LanguageTag.NO_SERVER
        val key = credentials.cacheServerKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@withContext LanguageTag.NO_KEY
        val body = buildJsonObject {
            put("title", request.title)
            put("artist", request.artist)
            if (request.album.isNotBlank()) put("album", request.album)
            if (request.durationMs > 0) put("durationMs", request.durationMs)
            request.spotifyTrackId?.let { put("spotifyId", it) }
            request.isrc?.let { put("isrc", it) }
            if (language == null) put("language", JsonNull) else put("language", language)
        }.toString()
        val call = Http.request("$base/v1/language", mapOf("Authorization" to "Bearer $key"))
            .newBuilder()
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            Http.execute(Http.client.newCall(call)) { response ->
                when {
                    response.isSuccessful -> LanguageTag.SAVED
                    response.code == 401 -> LanguageTag.WRONG_KEY
                    response.code == 403 -> LanguageTag.NOT_ADMIN
                    else -> LanguageTag.FAILED
                }
            }
        }.getOrDefault(LanguageTag.FAILED)
    }

    /**
     * A key only if one was given.
     *
     * A server on your own network should let a lookup through unauthenticated — nothing about
     * asking for lyrics can expose a credential, and the app is the only thing on the Wi-Fi
     * asking. One reachable from further away should not, so the header goes out when a key is
     * set and is otherwise absent rather than empty.
     */
    private fun headers(): Map<String, String> {
        val key = credentials.cacheServerKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: return HEADERS
        return HEADERS + ("Authorization" to "Bearer $key")
    }

    /**
     * Turn whatever the server sent into a document.
     *
     * Sniffed rather than declared, so a server that returns a bare TTML file and one that
     * wraps it in JSON both work. Sniffing is safe here because the three shapes cannot be
     * mistaken for each other: JSON opens with a brace, TTML with an angle bracket, and LRC
     * with a timestamp or plain text.
     */
    internal fun documentFrom(body: String): LyricsDocument? {
        val text = body.trim()
        if (text.isEmpty()) return null

        if (text.startsWith("{")) return fromEnvelope(text)
        return parse(text, format = null, label = displayName)
    }

    /**
     * `{"status": 200, "data": {"format": "ttml", "lyrics": "…", "source": "applemusic"}}`
     *
     * Every field is optional except the lyrics themselves. `source` is used only to say
     * where the lyrics came from under the last line, so the credit survives the hop
     * through the cache instead of every track claiming to come from a server.
     */
    private fun fromEnvelope(text: String): LyricsDocument? {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return null
        val data = runCatching { root["data"]?.jsonObject }.getOrNull() ?: root

        val lyrics = data.string("lyrics") ?: data.string("body") ?: return null
        val label = data.string("providerName")
            ?: data.string("source")?.let { "$displayName · $it" }
            ?: displayName

        return parse(lyrics.trim(), format = data.string("format")?.lowercase(), label = label)
    }

    private fun parse(text: String, format: String?, label: String): LyricsDocument? = when {
        format == "ttml" || text.startsWith("<") -> TtmlParser.parse(text, label, id)
        else -> LrcParser.toDocument(text, label, id)
            ?: LrcParser.plainToDocument(text, label, id)
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    private companion object {
        val HEADERS = mapOf("Accept" to "application/json, application/xml, text/plain")

        const val LANGUAGE_HEADER = "X-Lyrics-Language"
        val LANGUAGES = setOf("nan", "zh", "yue")
    }
}
