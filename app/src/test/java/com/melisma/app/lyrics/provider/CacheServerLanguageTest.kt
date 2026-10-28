package com.melisma.app.lyrics.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.melisma.app.lyrics.SpokenLanguageStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * The language a cache server reports for a track, and the tag the app sends it.
 *
 * The tag is the one thing the app writes to the server — see docs/CACHE-SERVER.md — so what it sends
 * and how it reads a refusal are pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheServerLanguageTest {

    private class Received(val requestLine: String, val headers: Map<String, String>, val body: String)

    /** Answers each request with the next queued status and extra headers, recording what it was sent. */
    private class Responder {
        val socket = ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        val replies = ConcurrentLinkedQueue<Pair<Int, String>>()
        val received = ConcurrentLinkedQueue<Received>()

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use {
                        val input = it.getInputStream()
                        // Content-Length counts bytes, and the title is Chinese, so read bytes.
                        fun line(): String? {
                            val bytes = java.io.ByteArrayOutputStream()
                            while (true) {
                                val b = input.read()
                                if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8)
                                if (b == '\n'.code) return bytes.toString(Charsets.UTF_8).trimEnd('\r')
                                bytes.write(b)
                            }
                        }
                        val requestLine = line().orEmpty()
                        val headers = HashMap<String, String>()
                        while (true) {
                            val header = line() ?: break
                            if (header.isEmpty()) break
                            val colon = header.indexOf(':')
                            if (colon > 0) headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
                        }
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = ByteArray(length).also { bytes -> var read = 0; while (read < length) read += input.read(bytes, read, length - read) }
                        received += Received(requestLine, headers, String(body, Charsets.UTF_8))
                        val (status, extra) = replies.poll() ?: (404 to "")
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $status X\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            flush()
                        }
                    }
                }
            }
        }

        val url get() = "http://127.0.0.1:${socket.localPort}"
    }

    private class Creds(override val cacheServerUrl: String?, override val cacheServerKey: String?) : ProviderCredentials {
        override val lrcLibBaseUrl = ""
        override val neteaseBaseUrl = ""
        override val amllBaseUrl = ""
        override val spotifyBrowserTokenEnabled = false
        override val neteaseCookie: String? = null
        override val musixmatchUserToken: String? = null
        override val appleDeveloperToken: String? = null
        override val appleMusicUserToken: String? = null
        override val appleStorefront = "us"
        override val spotifyWebToken: String? = null
        override var spDcCookie: String? = null
        override var musixmatchGuestToken: String? = null
        override var cachedSpotifyToken: String? = null
        override var cachedSpotifyTokenExpiresAt = 0L
    }

    private val server = Responder()
    private lateinit var languages: SpokenLanguageStore
    private val request = LyricsRequest(
        title = "愛到明仔載", artist = "蔡佩軒", album = "", durationMs = 208_000,
        spotifyTrackId = "5jmPdv7WGoWm5KeRM1WIAf", isrc = "TWA531900001",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "spoken-languages.json").delete()
        languages = SpokenLanguageStore(context)
    }

    @After
    fun tearDown() = server.socket.close()

    private fun provider(key: String? = "admin") = CacheServerProvider(Creds(server.url, key), languages)

    @Test
    fun `a tag is sent with the admin key, and names the recording`() = runBlocking {
        server.replies += 204 to ""
        assertEquals(CacheServerProvider.LanguageTag.SAVED, provider().putLanguage(request, "nan"))

        val sent = server.received.single()
        assertTrue(sent.requestLine.startsWith("PUT /v1/language "))
        assertEquals("Bearer admin", sent.headers["authorization"])
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals("nan", body["language"]!!.jsonPrimitive.content)
        assertEquals("5jmPdv7WGoWm5KeRM1WIAf", body["spotifyId"]!!.jsonPrimitive.content)
        // The server shares a tag across every release of the recording, which it knows by ISRC.
        assertEquals("TWA531900001", body["isrc"]!!.jsonPrimitive.content)
        assertEquals("愛到明仔載", body["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clearing a tag sends an explicit null`() = runBlocking {
        server.replies += 204 to ""
        provider().putLanguage(request, null)
        // The server treats a missing language as a mistake, so "decide for yourself" has to say so.
        assertEquals(JsonNull, Json.parseToJsonElement(server.received.single().body).jsonObject["language"])
    }

    @Test
    fun `a refusal is told apart from an outage`() = runBlocking {
        server.replies += 403 to ""
        assertEquals(CacheServerProvider.LanguageTag.NOT_ADMIN, provider(key = "a-user-key").putLanguage(request, "nan"))
        server.replies += 401 to ""
        assertEquals(CacheServerProvider.LanguageTag.WRONG_KEY, provider(key = "wrong").putLanguage(request, "nan"))
        server.replies += 500 to ""
        assertEquals(CacheServerProvider.LanguageTag.FAILED, provider().putLanguage(request, "nan"))
    }

    @Test
    fun `without a key nothing is sent`() = runBlocking {
        assertEquals(CacheServerProvider.LanguageTag.NO_KEY, provider(key = null).putLanguage(request, "nan"))
        assertTrue(server.received.isEmpty())
    }

    @Test
    fun `a lookup names the recording every way the app knows it`() = runBlocking {
        server.replies += 404 to ""
        provider(key = null).fetch(request.copy(playerId = "deezer:2981739661"))
        val line = server.received.poll()!!.requestLine
        assertTrue(line, line.startsWith("GET /v1/lyrics?"))
        assertTrue(line, "&spotifyId=5jmPdv7WGoWm5KeRM1WIAf" in line)
        assertTrue(line, "&isrc=TWA531900001" in line)
        assertTrue(line, "&playerId=deezer%3A2981739661" in line)
    }

    @Test
    fun `what is not known is not sent`() = runBlocking {
        server.replies += 404 to ""
        provider(key = null).fetch(request.copy(spotifyTrackId = null, isrc = null, playerId = null))
        val line = server.received.poll()!!.requestLine
        assertTrue(line, "spotifyId" !in line && "isrc" !in line && "playerId" !in line)
    }

    @Test
    fun `the recording the server resolved is reported, a miss included`() = runBlocking {
        server.replies += 404 to "X-Lyrics-Isrc: jp-vi0-24-03562\r\n"
        val learned = ArrayList<String>()
        val asked = request.copy(isrc = null).apply { onIsrc = { learned += it } }
        assertNull(provider(key = null).fetch(asked))
        assertEquals(listOf("JPVI02403562"), learned)
    }

    @Test
    fun `a header that is not an ISRC is ignored`() = runBlocking {
        server.replies += 404 to "X-Lyrics-Isrc: unknown\r\n"
        val learned = ArrayList<String>()
        provider(key = null).fetch(request.copy(isrc = null).apply { onIsrc = { learned += it } })
        assertTrue(learned.isEmpty())
    }

    @Test
    fun `the language the server reports is kept, a miss included`() = runBlocking {
        server.replies += 404 to "X-Lyrics-Language: nan\r\nX-Lyrics-Language-Source: tagged\r\n"
        assertNull(provider(key = null).fetch(request))
        assertEquals("nan", languages.get(request.cacheIdentity()))
    }

    @Test
    fun `a language the app does not know is ignored`() = runBlocking {
        server.replies += 404 to "X-Lyrics-Language: klingon\r\n"
        provider(key = null).fetch(request)
        assertNull(languages.get(request.cacheIdentity()))
    }
}
