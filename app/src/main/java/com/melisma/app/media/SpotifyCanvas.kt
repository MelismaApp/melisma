package com.melisma.app.media

import android.graphics.Bitmap
import com.melisma.app.lyrics.provider.Http
import com.melisma.app.lyrics.provider.ProviderCredentials
import com.melisma.app.lyrics.provider.SpotifyWebToken
import com.melisma.app.settings.CanvasMode
import com.melisma.app.settings.Settings
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Spotify Canvas: the short looping video Spotify shows behind some tracks.
 *
 * Asked of `canvaz-cache` with the same web-player token the lyrics use, and nothing else. Some
 * clients also send a `client-token` minted from `clienttoken.spotify.com`; this does not, for the
 * same reason the lyrics provider does not — see [SpotifyWebToken] — and the endpoint answered
 * without one when checked. If Spotify starts insisting, the answer is a refusal, and a refusal
 * here only means the chosen background shows instead.
 *
 * The token goes to `spclient.wg.spotify.com` and nowhere else. The video itself is on a public
 * CDN and is fetched with no credential at all.
 */
class SpotifyCanvas(
    private val credentials: ProviderCredentials,
    /** The same gate as the other Spotify extras, so one policy decides all of them. */
    private val available: () -> Boolean,
    private val base: String = "https://spclient.wg.spotify.com",
) {

    /** Track id to its Canvas, or to null for a track Spotify has no video for. */
    private val answers = LinkedHashMap<String, CanvasLink?>()

    val isAvailable: Boolean get() = available()

    /** Whether Spotify has said, video or none, rather than not having been asked or not answering. */
    fun hasAnswered(trackId: String): Boolean = synchronized(answers) { answers.containsKey(trackId) }

    /** The track's Canvas, or null when it has none or it could not be asked. */
    suspend fun canvasFor(trackId: String): CanvasLink? = withContext(Dispatchers.IO) {
        if (!available()) return@withContext null
        synchronized(answers) {
            if (answers.containsKey(trackId)) return@withContext answers[trackId]
        }

        val token = SpotifyWebToken.get(credentials) ?: return@withContext null
        var answer = ask(trackId, token)
        if (answer == Answer.Refused) {
            // An expired token looks much the same as a refusal, so one retry with a fresh one.
            SpotifyWebToken.refresh(credentials)?.let { answer = ask(trackId, it) }
        }

        when (val settled = answer) {
            is Answer.Found -> remember(trackId, settled.link)
            Answer.None -> remember(trackId, null)
            // Not remembered: a refusal or an outage says nothing about the track.
            Answer.Refused, Answer.Failed -> null
        }
    }

    private fun remember(trackId: String, link: CanvasLink?): CanvasLink? {
        synchronized(answers) {
            answers[trackId] = link
            while (answers.size > 300) answers.remove(answers.keys.first())
        }
        return link
    }

    private sealed interface Answer {
        data class Found(val link: CanvasLink) : Answer
        data object None : Answer
        data object Refused : Answer
        data object Failed : Answer
    }

    private fun ask(trackId: String, token: String): Answer = runCatching {
        val request = Request.Builder()
            .url("$base/canvaz-cache/v0/canvases")
            .header("Authorization", "Bearer $token")
            .header("App-Platform", "WebPlayer")
            .header("User-Agent", SpotifyWebToken.WEB_USER_AGENT)
            .header("Accept", "application/protobuf")
            .post(canvasRequest(trackId).toRequestBody(PROTOBUF))
            .build()
        Http.client.newCall(request).execute().use { response ->
            when {
                response.code == 401 || response.code == 403 -> Answer.Refused
                !response.isSuccessful -> Answer.Failed
                else -> {
                    val links = canvasLinks(response.body?.bytes() ?: ByteArray(0))
                    val link = links["spotify:track:$trackId"] ?: links.values.firstOrNull()
                    if (link != null) Answer.Found(link) else Answer.None
                }
            }
        }
    }.getOrDefault(Answer.Failed)

    companion object {
        private val PROTOBUF = "application/x-protobuf".toMediaType()

        /**
         * `EntityCanvazRequest { repeated Entity entities = 1; }`, `Entity { string entity_uri = 1; }`.
         */
        internal fun canvasRequest(trackId: String): ByteArray {
            val entity = ByteArrayOutputStream().apply { field(1, "spotify:track:$trackId".toByteArray()) }
            return ByteArrayOutputStream().apply { field(1, entity.toByteArray()) }.toByteArray()
        }

        /**
         * Entity URI to Canvas, from an `EntityCanvazResponse`.
         *
         * `repeated Canvaz canvases = 1`, and in each `url = 2`, `entity_uri = 5`, and stills of it
         * at `13` as `{ height = 1, width = 2, url = 3 }`. Anything that is not a video on Spotify's
         * CDN is dropped: an image Canvas has a `.jpg` URL, and a URL pointing anywhere else is not
         * something to download on the strength of this response.
         */
        internal fun canvasLinks(bytes: ByteArray): Map<String, CanvasLink> {
            val out = LinkedHashMap<String, CanvasLink>()
            val top = ProtoReader(bytes)
            while (top.hasMore()) {
                val (field, wire) = top.tag() ?: break
                if (field == 1 && wire == 2) {
                    val canvas = ProtoReader(top.bytes() ?: break)
                    var url: String? = null
                    var uri: String? = null
                    var poster: String? = null
                    var posterArea = 0L
                    while (canvas.hasMore()) {
                        val (f, w) = canvas.tag() ?: break
                        when {
                            f == 2 && w == 2 -> url = canvas.bytes()?.decodeToString()
                            f == 5 && w == 2 -> uri = canvas.bytes()?.decodeToString()
                            f == 13 && w == 2 -> {
                                val still = readStill(canvas.bytes() ?: break)
                                if (still != null && still.second > posterArea) {
                                    poster = still.first
                                    posterArea = still.second
                                }
                            }
                            !canvas.skip(w) -> break
                        }
                    }
                    if (url != null && isCanvasVideo(url)) out[uri ?: url] = CanvasLink(url, poster)
                } else if (!top.skip(wire)) {
                    break
                }
            }
            return out
        }

        /** One still of a Canvas: its URL and its area, so the largest can be picked. */
        private fun readStill(bytes: ByteArray): Pair<String, Long>? {
            val still = ProtoReader(bytes)
            var height = 0L
            var width = 0L
            var url: String? = null
            while (still.hasMore()) {
                val (f, w) = still.tag() ?: break
                when {
                    f == 1 && w == 0 -> height = still.varint() ?: break
                    f == 2 && w == 0 -> width = still.varint() ?: break
                    f == 3 && w == 2 -> url = still.bytes()?.decodeToString()
                    !still.skip(w) -> break
                }
            }
            return url?.takeIf(::isCanvasStill)?.let { it to width * height }
        }

        /** A still of a Canvas: Spotify's image CDN, over https. */
        internal fun isCanvasStill(url: String): Boolean = runCatching {
            val parsed = URI(url)
            parsed.scheme == "https" &&
                parsed.host.orEmpty().let { it == "i.scdn.co" || it.endsWith(".scdn.co") } &&
                parsed.path.orEmpty().startsWith("/image/")
        }.getOrDefault(false)

        internal fun isCanvasVideo(url: String): Boolean = runCatching {
            val parsed = URI(url)
            parsed.scheme == "https" &&
                (parsed.host == "canvaz.scdn.co" || parsed.host.orEmpty().endsWith(".scdn.co")) &&
                parsed.path.orEmpty().endsWith(".mp4")
        }.getOrDefault(false)

        private fun ByteArrayOutputStream.field(number: Int, value: ByteArray) {
            varint((number shl 3) or 2)
            varint(value.size)
            write(value)
        }

        private fun ByteArrayOutputStream.varint(value: Int) {
            var v = value
            while (v and 0x7F.inv() != 0) {
                write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            write(v)
        }
    }
}

/** Just enough protobuf to walk a message: tags, lengths, and skipping what is not needed. */
private class ProtoReader(private val data: ByteArray) {
    private var at = 0

    fun hasMore(): Boolean = at < data.size

    fun tag(): Pair<Int, Int>? {
        val key = varint() ?: return null
        return (key ushr 3).toInt() to (key and 7).toInt()
    }

    fun bytes(): ByteArray? {
        val length = varint() ?: return null
        if (length < 0 || at + length > data.size) return null
        return data.copyOfRange(at, (at + length).toInt()).also { at += length.toInt() }
    }

    /** False for a wire type this does not know, which means the message cannot be walked further. */
    fun skip(wire: Int): Boolean = when (wire) {
        0 -> varint() != null
        1 -> advance(8)
        2 -> bytes() != null
        5 -> advance(4)
        else -> false
    }

    private fun advance(count: Int): Boolean {
        if (at + count > data.size) return false
        at += count
        return true
    }

    fun varint(): Long? {
        var result = 0L
        var shift = 0
        while (at < data.size && shift < 64) {
            val b = data[at++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return null
    }
}

/**
 * Canvas videos on disk, so a track played twice is downloaded once.
 *
 * A Canvas is a few seconds of video, usually one to three megabytes. Kept to [maxBytes], oldest
 * played first out.
 */
class CanvasVideoCache(private val dir: File, private val maxBytes: Long = 80L * 1024 * 1024) {

    /** A longer call budget than [Http.client]'s: a few megabytes on a slow connection takes a while. */
    private val client: OkHttpClient = Http.client.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /** The video if it is already on disk, without downloading anything. */
    suspend fun cached(url: String): File? = withContext(Dispatchers.IO) {
        if (!SpotifyCanvas.isCanvasVideo(url)) return@withContext null
        File(dir, sha1(url) + ".mp4").takeIf { it.isFile && it.length() > 0 }
            ?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    /** A still of the Canvas, a few dozen kilobytes, kept in memory only. */
    suspend fun poster(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (!SpotifyCanvas.isCanvasStill(url)) return@withContext null
        runCatching {
            client.newCall(Http.request(url, mapOf("Accept" to "image/*"))).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.bytes()?.let(ArtworkDecoding::decode)
            }
        }.getOrNull()
    }

    suspend fun fileFor(url: String): File? = withContext(Dispatchers.IO) {
        if (!SpotifyCanvas.isCanvasVideo(url)) return@withContext null
        dir.mkdirs()
        val file = File(dir, sha1(url) + ".mp4")
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }

        val partial = File(dir, file.name + ".part")
        // A blocking read ignores cancellation, so it is checked between chunks: leaving the app
        // or skipping the track stops the download rather than finishing it for nobody.
        val ok = runCatching { download(url, partial) { !isActive } }.getOrDefault(false)

        if (!ok || !partial.renameTo(file)) {
            partial.delete()
            return@withContext null
        }
        trim()
        file
    }

    /** True when [target] holds the whole video; false for a failure, a cancellation or anything over the cap. */
    private fun download(url: String, target: File, cancelled: () -> Boolean): Boolean {
        client.newCall(Http.request(url)).execute().use { response ->
            val body = response.body ?: return false
            if (!response.isSuccessful || body.contentLength() > MAX_VIDEO_BYTES) return false
            var written = 0L
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) return false
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > MAX_VIDEO_BYTES) return false
                        out.write(buffer, 0, read)
                    }
                }
            }
            return written > 0
        }
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") } ?: return
        var total = files.sumOf { it.length() }
        for (oldest in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= oldest.length()
            oldest.delete()
        }
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** A Canvas is seconds long; anything past this is not one. */
        const val MAX_VIDEO_BYTES = 20L * 1024 * 1024
    }
}

/** A track's Canvas: the video, and a still of it to show while the video downloads. */
data class CanvasLink(val videoUrl: String, val posterUrl: String? = null)

/**
 * A Canvas to show, and the track it belongs to.
 *
 * [poster] alone while the video downloads, or if it never does; [file] once it is on disk.
 */
data class CanvasVideo(val trackId: String, val file: File?, val poster: Bitmap? = null)

/**
 * Whether anything on screen would show a Canvas, so one is worth fetching.
 *
 * The screens' own rules, restated: a floating window holding its background still shows none,
 * and the car shows only Blurred, which needs the render effect.
 */
internal fun canvasShowable(
    settings: Settings,
    window: Boolean,
    popup: Boolean,
    car: Boolean,
    blurAvailable: Boolean,
): Boolean {
    if (settings.canvasMode == CanvasMode.OFF) return false
    val inWindow = window && !(popup && settings.popupStillBackground)
    val inCar = car && settings.canvasMode == CanvasMode.BLURRED && blurAvailable
    return inWindow || inCar
}
