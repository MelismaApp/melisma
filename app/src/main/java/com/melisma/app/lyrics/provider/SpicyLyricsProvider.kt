package com.melisma.app.lyrics.provider

import com.melisma.app.lyrics.model.Credit
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.parse.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Spicy Lyrics' own API: community-made syncs first, then Apple Music's and Spotify's lyrics.
 *
 * One endpoint, asked by Spotify id or ISRC, so the recording the app has identified is looked up
 * exactly; a title search is the fallback. The response names which catalogue answered, and that
 * decides the credit: a community sync is somebody's work, and its terms want the maker and the
 * uploader named and linked wherever the words are shown. The commercial sources get the provider
 * line only.
 *
 * The key is the user's own. Melisma is registered as an app template, so each person opens the
 * app's catalog page, presses Add and gets a client key of their own with its own limit — the
 * arrangement the API's terms ask for when an app runs on other people's devices. A secret key
 * (`sl_sk_`) belongs on a server and is refused here rather than sent anywhere.
 *
 * https://developers.spicylyrics.org/docs
 */
class SpicyLyricsProvider(
    private val credentials: ProviderCredentials,
    private val base: String = BASE,
) : LyricsProvider {

    override val id = "spicylyrics"
    override val displayName = "Spicy Lyrics"
    override val canBeWordSynced = true
    override val link = "https://spicylyrics.org"
    override val creditRequired = true

    /** The pasted value, if it is a client key. Anything else leaves the source dormant. */
    private val clientKey: String?
        get() = bearerValue(credentials.spicyLyricsKey)?.takeIf { CLIENT_KEY.matches(it) }

    override val isConfigured: Boolean get() = clientKey != null

    override val unavailableReason: String?
        get() = bearerValue(credentials.spicyLyricsKey)?.let { key ->
            when {
                key.startsWith("sl_sk_") ->
                    "That is a secret key, which belongs on a server and must not be in an app. " +
                        "Press Add on Melisma's page in the Spicy Lyrics catalog for a client key."
                !CLIENT_KEY.matches(key) -> "Not a Spicy Lyrics client key — one begins with sl_pk_."
                else -> null
            }
        }

    @Volatile
    override var noMatchReason: String? = null
        private set

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? = withContext(Dispatchers.IO) {
        noMatchReason = null
        val key = clientKey ?: run {
            noMatchReason = "No client key"
            return@withContext null
        }
        // The Spotify id names the exact track and finds community syncs an ISRC can miss, so it
        // comes first; the ISRC still names the recording. Only without either is the title searched.
        val trackId = request.spotifyTrackId?.takeIf { it.isNotBlank() }
            ?: request.isrc?.takeIf { it.isNotBlank() }?.trim()?.uppercase()
            ?: search(request, key)
            ?: return@withContext null
        lyrics(trackId, request, key)
    }

    private suspend fun lyrics(trackId: String, request: LyricsRequest, key: String): LyricsDocument? {
        var status = 0
        val body = Http.get("$base/v1/lyrics/${Http.encode(trackId)}", headers(key), onStatus = { status = it }) {
            runCatching { Http.json.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        if (body == null) {
            noMatchReason = when (status) {
                401, 403 -> "Spicy Lyrics refused the key"
                404 -> "Spicy Lyrics has no lyrics for this track"
                else -> "Spicy Lyrics answered HTTP $status"
            }
            return null
        }
        val content = body["Body"]?.asObject()
        if (body["Status"]?.jsonPrimitive?.contentOrNull != "200" || content == null) {
            noMatchReason = "Spicy Lyrics has no lyrics for this track"
            return null
        }
        // The identity is worth keeping whether or not the lyrics parse.
        content["ids"]?.asObject()?.string("isrc")?.let { request.onIsrc?.invoke(it) }

        val ttml = SpicyLyricsBody.toTtml(content) ?: run {
            noMatchReason = "Spicy Lyrics answered with nothing readable"
            return null
        }
        val document = TtmlParser.parse(ttml, displayName, id) ?: run {
            noMatchReason = "Spicy Lyrics answered with nothing readable"
            return null
        }
        return document.copy(credits = credits(content))
    }

    /**
     * The first result, when the API itself calls it an exact match. A `likely` or `version` hit
     * is another recording more often than not, and another recording has other timings.
     */
    private suspend fun search(request: LyricsRequest, key: String): String? {
        if (request.title.isBlank()) {
            noMatchReason = "Nothing to search with"
            return null
        }
        val url = buildString {
            append("$base/v1/tracks/search?title=").append(Http.encode(request.title))
            if (request.artist.isNotBlank()) append("&artist=").append(Http.encode(request.artist))
            if (request.album.isNotBlank()) append("&album=").append(Http.encode(request.album))
            if (request.durationMs > 0) {
                append("&duration=").append(String.format(Locale.ROOT, "%.3f", request.durationMs / 1000.0))
            }
        }
        val first = Http.get(url, headers(key)) { raw ->
            runCatching { Http.json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?.get("Body")?.asObject()?.get("results")?.asArray()?.firstOrNull()?.asObject()
        }
        if (first == null) {
            noMatchReason = "Spicy Lyrics does not know this track"
            return null
        }
        if (first.string("tier") != "exact") {
            noMatchReason = "Spicy Lyrics' closest match is not this recording"
            return null
        }
        first["ids"]?.asObject()?.string("isrc")?.let { request.onIsrc?.invoke(it) }
        return first.string("id")
    }

    private fun headers(key: String) = mapOf(
        "Authorization" to "Bearer $key",
        "Accept" to "application/json",
    )

    companion object {
        const val BASE = "https://api.spicylyrics.org"

        /** Where a user gets a key of their own: Melisma's page in the catalog. */
        const val CATALOG_URL = "https://developers.spicylyrics.org/catalog/melisma"

        private val CLIENT_KEY = Regex("sl_pk_[A-Za-z0-9_-]{8,}")

        /**
         * Who to name under the words, by the API's rule: the provider always, and for a community
         * sync the maker and the uploader too, each linked where a profile is given. The commercial
         * catalogues name nobody, and a response that does not say where it came from says so.
         */
        fun credits(body: JsonObject): List<Credit> {
            val source = body.string("source")?.trim()?.lowercase()
            if (source == null) return listOf(Credit("Source not identified"))
            if (source != "spicy_lyrics") return emptyList()
            val attribution = body["UploadAttribution"]?.asObject() ?: return emptyList()
            val maker = attribution.contributor("Maker")
            val uploader = attribution.contributor("Uploader")
            return when {
                maker != null && uploader != null && maker.first == uploader.first ->
                    listOf(Credit("Made and uploaded by ${maker.first}", maker.second))
                else -> listOfNotNull(
                    maker?.let { Credit("Made by ${it.first}", it.second) },
                    uploader?.let { Credit("Uploaded by ${it.first}", it.second) },
                )
            }
        }

        /** A contributor's name and profile link, when the entry names anyone. */
        private fun JsonObject.contributor(role: String): Pair<String, String?>? {
            val entry = this[role]?.asObject() ?: return null
            val name = entry.string("username")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val url = entry.string("url")?.takeIf { it.startsWith("https://") }
            return name to url
        }

    }
}

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun JsonElement.asArray(): JsonArray? = this as? JsonArray
private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.number(name: String): Double? =
    this[name]?.jsonPrimitive?.takeIf { !it.isString }?.doubleOrNull
private fun JsonObject.flag(name: String): Boolean? =
    this[name]?.jsonPrimitive?.takeIf { !it.isString }?.booleanOrNull

/**
 * The API's own lyric shape, written out as the Apple-style TTML [TtmlParser] reads.
 *
 * Three shapes, named by `Type`. `Syllable` content is a list of lines, each with a lead vocal of
 * timed syllables and any backing groups; `Line` content times whole lines; `Static` has only
 * `Lines[].Text`. Translations and romanizations ride along per line or per syllable. Going through
 * TTML rather than building lines directly keeps one path for duets, backing vocals, readings and
 * translations, which the parser already gets right for Apple's and lrc.red's files.
 */
object SpicyLyricsBody {

    fun toTtml(body: JsonObject): String? {
        val type = body.string("Type")?.lowercase()
        val content = body["Content"]?.asArray()
        val lines = body["Lines"]?.asArray()
        return when {
            content != null && type != "static" -> timed(body, content, syllable = type != "line")
            lines != null -> static(lines)
            else -> null
        }
    }

    private fun timed(body: JsonObject, content: JsonArray, syllable: Boolean): String? {
        val paragraphs = StringBuilder()
        val readings = StringBuilder()
        var count = 0
        for (element in content) {
            val item = element.asObject() ?: continue
            if (item.string("Type").equals("Interlude", ignoreCase = true)) continue
            val key = "L${count + 1}"
            val paragraph = if (syllable) {
                syllableLine(key, item, readings)
            } else {
                plainLine(key, item, readings)
            } ?: continue
            paragraphs.append(paragraph)
            count++
        }
        if (count == 0) return null

        val writers = body["SongWriters"]?.asArray().orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .joinToString("") { "<songwriter>${xml(it)}</songwriter>" }
        val transliterations = if (readings.isEmpty()) {
            ""
        } else {
            "<transliterations><transliteration>$readings</transliteration></transliterations>"
        }
        return "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" " +
            "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" " +
            "itunes:timing=\"${if (syllable) "Word" else "Line"}\">" +
            "<head><metadata><ttm:agent type=\"person\" xml:id=\"v1\"/><ttm:agent type=\"person\" xml:id=\"v2\"/>" +
            "<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
            "<songwriters>$writers</songwriters>$transliterations</iTunesMetadata></metadata></head>" +
            "<body><div>$paragraphs</div></body></tt>"
    }

    /** A word-timed line: the lead's syllables, then each backing group, then the translation. */
    private fun syllableLine(key: String, item: JsonObject, readings: StringBuilder): String? {
        val lead = item["Lead"]?.asObject()
        val leadSyllables = lead?.get("Syllables")?.asArray()?.mapNotNull { it.asObject() }.orEmpty()
        val groups = item["Background"]?.asArray()?.mapNotNull { group ->
            group.asObject()?.get("Syllables")?.asArray()?.mapNotNull { it.asObject() }?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        if (leadSyllables.isEmpty() && groups.isEmpty()) return null

        val all = leadSyllables + groups.flatten()
        val begin = lead?.number("StartTime") ?: item.number("StartTime") ?: all.minOf { it.number("StartTime") ?: 0.0 }
        val end = lead?.number("EndTime") ?: item.number("EndTime") ?: all.maxOf { it.number("EndTime") ?: 0.0 }

        val spans = StringBuilder()
        spans.append(spans(leadSyllables))
        for (group in groups) spans.append("<span ttm:role=\"x-bg\">").append(spans(group)).append("</span>")
        spans.append(translation(lead?.string("TranslatedText") ?: item.string("TranslatedText")))

        // Readings keyed to their syllables, the way Apple attaches its romanizations, so the
        // highlight can carry the reading along word by word.
        val read = readings(key, leadSyllables)
        if (read.isNotEmpty()) readings.append("<text for=\"$key\">").append(read).append("</text>")

        return "<p begin=\"${seconds(begin)}\" end=\"${seconds(end)}\" itunes:key=\"$key\"" +
            " ttm:agent=\"${agent(item)}\">$spans</p>"
    }

    /** A line-timed line: its text, with the translation and the reading it may carry. */
    private fun plainLine(key: String, item: JsonObject, readings: StringBuilder): String? {
        val text = item.string("Text")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val begin = item.number("StartTime") ?: return null
        val end = item.number("EndTime") ?: begin
        item.string("TransliteratedText")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            readings.append("<text for=\"$key\">").append(xml(it)).append("</text>")
        }
        return "<p begin=\"${seconds(begin)}\" end=\"${seconds(end)}\" itunes:key=\"$key\"" +
            " ttm:agent=\"${agent(item)}\">${xml(text)}${translation(item.string("TranslatedText"))}</p>"
    }

    /** Untimed lines, each with its translation when there is one. */
    private fun static(lines: JsonArray): String? {
        val paragraphs = lines.mapNotNull { it.asObject() }.mapNotNull { line ->
            val text = line.string("Text")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            "<p>${xml(text)}${translation(line.string("TranslatedText"))}</p>"
        }
        if (paragraphs.isEmpty()) return null
        return "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" " +
            "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" itunes:timing=\"None\">" +
            "<body><div>${paragraphs.joinToString("")}</div></body></tt>"
    }

    /**
     * Timed spans with a space between words. The API marks a syllable that runs into the next
     * one; TTML says it with the whitespace between spans, so the space goes after every syllable
     * that does not.
     */
    private fun spans(syllables: List<JsonObject>): String = buildString {
        syllables.forEachIndexed { index, syllable ->
            val text = syllable.string("Text") ?: return@forEachIndexed
            val begin = syllable.number("StartTime") ?: return@forEachIndexed
            val end = syllable.number("EndTime") ?: begin
            append("<span begin=\"${seconds(begin)}\" end=\"${seconds(end)}\">${xml(text)}</span>")
            if (index < syllables.lastIndex && syllable.flag("IsPartOfWord") != true) append(' ')
        }
    }

    private fun readings(key: String, syllables: List<JsonObject>): String = buildString {
        syllables.forEachIndexed { index, syllable ->
            val reading = syllable.string("TransliteratedText")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEachIndexed
            append("<span for=\"$key.${index + 1}\">${xml(reading)}</span>")
            if (index < syllables.lastIndex && syllable.flag("IsPartOfWord") != true) append(' ')
        }
    }

    private fun translation(text: String?): String =
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { "<span ttm:role=\"x-translation\">${xml(it)}</span>" }.orEmpty()

    /** The second voice for a line the API pins to the far edge, as Apple does with a duet. */
    private fun agent(item: JsonObject): String = if (item.flag("OppositeAligned") == true) "v2" else "v1"

    private fun seconds(value: Double) = String.format(Locale.ROOT, "%.3f", value)

    private fun xml(value: String) = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
