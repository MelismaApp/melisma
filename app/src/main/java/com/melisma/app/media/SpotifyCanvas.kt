package com.melisma.app.media

import com.melisma.app.lyrics.provider.Http
import com.melisma.app.lyrics.provider.ProviderCredentials
import com.melisma.app.lyrics.provider.SpotifyWebToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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

    /** Track id to video URL, or to null for a track Spotify has no video for. */
    private val answers = LinkedHashMap<String, String?>()

    val isAvailable: Boolean get() = available()

    /** The track's Canvas video URL, or null when it has none or it could not be asked. */
    suspend fun videoFor(trackId: String): String? = withContext(Dispatchers.IO) {
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
            is Answer.Found -> remember(trackId, settled.url)
            Answer.None -> remember(trackId, null)
            // Not remembered: a refusal or an outage says nothing about the track.
            Answer.Refused, Answer.Failed -> null
        }
    }

    private fun remember(trackId: String, url: String?): String? {
        synchronized(answers) {
            answers[trackId] = url
            while (answers.size > 300) answers.remove(answers.keys.first())
        }
        return url
    }

    private sealed interface Answer {
        data class Found(val url: String) : Answer
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
                    val urls = canvasUrls(response.body?.bytes() ?: ByteArray(0))
                    val url = urls["spotify:track:$trackId"] ?: urls.values.firstOrNull()
                    if (url != null) Answer.Found(url) else Answer.None
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
         * Entity URI to video URL, from an `EntityCanvazResponse`.
         *
         * `repeated Canvaz canvases = 1`, and in each `url = 2`, `entity_uri = 5`. Anything that is not
         * a video on Spotify's CDN is dropped: an image Canvas has a `.jpg` URL, and a URL pointing
         * anywhere else is not something to download on the strength of this response.
         */
        internal fun canvasUrls(bytes: ByteArray): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val top = ProtoReader(bytes)
            while (top.hasMore()) {
                val (field, wire) = top.tag() ?: break
                if (field == 1 && wire == 2) {
                    val canvas = ProtoReader(top.bytes() ?: break)
                    var url: String? = null
                    var uri: String? = null
                    while (canvas.hasMore()) {
                        val (f, w) = canvas.tag() ?: break
                        when {
                            f == 2 && w == 2 -> url = canvas.bytes()?.decodeToString()
                            f == 5 && w == 2 -> uri = canvas.bytes()?.decodeToString()
                            !canvas.skip(w) -> break
                        }
                    }
                    if (url != null && isCanvasVideo(url)) out[uri ?: url] = url
                } else if (!top.skip(wire)) {
                    break
                }
            }
            return out
        }

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

    private fun varint(): Long? {
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

    suspend fun fileFor(url: String): File? = withContext(Dispatchers.IO) {
        if (!SpotifyCanvas.isCanvasVideo(url)) return@withContext null
        dir.mkdirs()
        val file = File(dir, sha1(url) + ".mp4")
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }

        val partial = File(dir, file.name + ".part")
        val ok = runCatching { download(url, partial) }.getOrDefault(false)

        if (!ok || !partial.renameTo(file)) {
            partial.delete()
            return@withContext null
        }
        trim()
        file
    }

    /** True when [target] holds the whole video; false for a failure or anything over the cap. */
    private fun download(url: String, target: File): Boolean {
        client.newCall(Http.request(url)).execute().use { response ->
            val body = response.body ?: return false
            if (!response.isSuccessful || body.contentLength() > MAX_VIDEO_BYTES) return false
            var written = 0L
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
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

/** A Canvas video ready to play, and the track it belongs to. */
data class CanvasVideo(val trackId: String, val file: File)
