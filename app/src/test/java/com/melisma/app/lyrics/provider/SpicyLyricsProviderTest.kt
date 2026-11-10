package com.melisma.app.lyrics.provider

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpicyLyricsProviderTest {

    /** Answers by path prefix, recording each request line and its Authorization header. */
    private class Server {
        val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val routes = HashMap<String, Pair<Int, String>>()
        val requests = ConcurrentLinkedQueue<String>()
        val authorizations = ConcurrentLinkedQueue<String>()

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use {
                        val reader = it.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Authorization:", ignoreCase = true)) {
                                authorizations += header.substringAfter(':').trim()
                            }
                        }
                        requests += requestLine
                        val path = requestLine.split(' ').getOrNull(1).orEmpty()
                        val (status, body) = routes.entries.firstOrNull { path.startsWith(it.key) }?.value ?: (404 to "")
                        val bytes = body.toByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }

        val url get() = "http://127.0.0.1:${socket.localPort}"
    }

    private class Credentials(override val spicyLyricsKey: String?) : ProviderCredentials {
        override var spDcCookie: String? = null
        override val spotifyWebToken: String? = null
        override val cacheServerUrl: String? = null
        override val cacheServerKey: String? = null
        override val spotifyBrowserTokenEnabled = false
        override val lrcLibBaseUrl = Settings.DEFAULT_LRCLIB_URL
        override val neteaseBaseUrl = Settings.DEFAULT_NETEASE_URL
        override val amllBaseUrl = Settings.DEFAULT_AMLL_URL
        override val neteaseCookie: String? = null
        override val musixmatchUserToken: String? = null
        override val appleDeveloperToken: String? = null
        override val appleMusicUserToken: String? = null
        override val appleStorefront = "us"
        override var musixmatchGuestToken: String? = null
        override var cachedSpotifyToken: String? = null
        override var cachedSpotifyTokenExpiresAt = 0L
    }

    private val server = Server()
    private val key = "sl_pk_test_key_0123456789"
    private val provider = SpicyLyricsProvider(Credentials(key), server.url)

    @After
    fun tearDown() = server.socket.close()

    private val request = LyricsRequest(title = "怪獣", artist = "サカナクション", album = "怪獣", durationMs = 252_722)

    /** A community sync as the API serves it: a duet line, a backing group, a reading and a translation. */
    private val community = """{"Status":200,"Type":"object","Body":{
        "Type":"Syllable","SongWriters":["Ichiro Yamaguchi"],"StartTime":1.133,"EndTime":5.0,
        "id":"4uLU6hMCjMI75M1A2tKUQC","ids":{"spotify":"4uLU6hMCjMI75M1A2tKUQC","isrc":"JPVI02403562"},
        "source":"spicy_lyrics",
        "UploadAttribution":{
          "Uploader":{"id":"1","username":"spikerko","url":"https://spicylyrics.org/uid/1"},
          "Maker":{"id":"2","username":"gc","url":"https://spicylyrics.org/uid/2"}},
        "Content":[
          {"Type":"Vocal","OppositeAligned":false,"Lead":{"StartTime":1.133,"EndTime":2.979,"TranslatedText":"Over and over again",
            "Syllables":[
              {"Text":"何度","StartTime":1.133,"EndTime":1.757,"IsPartOfWord":true,"TransliteratedText":"nando"},
              {"Text":"で","StartTime":1.757,"EndTime":2.2,"IsPartOfWord":true,"TransliteratedText":"de"},
              {"Text":"も","StartTime":2.2,"EndTime":2.979,"IsPartOfWord":false,"TransliteratedText":"mo"}]},
           "Background":[{"StartTime":2.0,"EndTime":2.9,"Syllables":[{"Text":"ah","StartTime":2.0,"EndTime":2.9,"IsPartOfWord":false}]}]},
          {"Type":"Interlude","StartTime":3.0,"EndTime":3.9},
          {"Type":"Vocal","OppositeAligned":true,"Lead":{"StartTime":4.0,"EndTime":5.0,
            "Syllables":[
              {"Text":"to","StartTime":4.0,"EndTime":4.4,"IsPartOfWord":true},
              {"Text":"gether","StartTime":4.4,"EndTime":5.0,"IsPartOfWord":false}]}}
        ]}}"""

    @Test
    fun `a Spotify id is one exact request with the key, and the sync comes through whole`() = runBlocking {
        server.routes["/v1/lyrics/4uLU6hMCjMI75M1A2tKUQC"] = 200 to community
        val learned = ArrayList<String>()
        val document = provider.fetch(
            request.copy(spotifyTrackId = "4uLU6hMCjMI75M1A2tKUQC", isrc = "JPVI02403562").apply { onIsrc = { learned += it } },
        )!!

        assertEquals(listOf("GET /v1/lyrics/4uLU6hMCjMI75M1A2tKUQC HTTP/1.1"), server.requests.toList())
        assertEquals(listOf("Bearer $key"), server.authorizations.toList())
        assertEquals(LyricsKind.SYLLABLE, document.kind)
        assertEquals("Spicy Lyrics", document.providerName)
        assertEquals(listOf("Ichiro Yamaguchi"), document.songWriters)
        assertEquals(listOf("JPVI02403562"), learned)

        val vocal = document.lines.filter { !it.isInterlude }
        val first = vocal.first { it.role == LineRole.LEAD }
        assertEquals("何度でも", first.text)
        assertEquals(listOf("何度", "で", "も"), first.syllables.map { it.text })
        // Japanese is written without spaces, so each syllable runs into the next.
        assertEquals(listOf(false, true, true), first.syllables.map { it.partOfWord })
        assertEquals(listOf("nando", "de", "mo"), first.syllables.map { it.romanized })
        assertEquals("Over and over again", first.translated)
        assertFalse(first.oppositeAligned)
        assertTrue(vocal.any { it.role == LineRole.BACKGROUND && it.text == "ah" })
        val duet = vocal.first { it.text == "together" }
        assertTrue(duet.oppositeAligned)
        assertEquals(listOf(false, true), duet.syllables.map { it.partOfWord })
    }

    @Test
    fun `a community sync credits and links its maker and uploader`() = runBlocking {
        server.routes["/v1/lyrics/"] = 200 to community
        val document = provider.fetch(request.copy(spotifyTrackId = "x"))!!

        assertEquals(listOf("Made by gc", "Uploaded by spikerko"), document.credits.map { it.text })
        assertEquals(
            listOf("https://spicylyrics.org/uid/2", "https://spicylyrics.org/uid/1"),
            document.credits.map { it.url },
        )
    }

    @Test
    fun `a commercial source is named as itself, credits nobody, and an unnamed one says so`() = runBlocking {
        val apple = community.replace("\"source\":\"spicy_lyrics\"", "\"source\":\"apple_music\"")
        server.routes["/v1/lyrics/"] = 200 to apple
        val fromApple = provider.fetch(request.copy(spotifyTrackId = "x"))!!
        assertEquals("Apple Music via Spicy Lyrics", fromApple.providerName)
        assertEquals("spicylyrics", fromApple.providerId)
        assertEquals(emptyList<Any>(), fromApple.credits)

        server.routes["/v1/lyrics/"] = 200 to community.replace("\"source\":\"spicy_lyrics\",", "")
        val unnamed = provider.fetch(request.copy(spotifyTrackId = "x"))!!
        assertEquals("Spicy Lyrics (source not identified)", unnamed.providerName)
        assertEquals(emptyList<Any>(), unnamed.credits)
    }

    @Test
    fun `a backing group keeps its translation and readings, and a half-romanized line reads whole`() = runBlocking {
        server.routes["/v1/lyrics/"] = 200 to """{"Status":200,"Body":{"Type":"Syllable","source":"spicy_lyrics","id":"x","ids":{"spotify":"x"},
            "Content":[{"Type":"Vocal","Lead":{"StartTime":1.0,"EndTime":2.0,"TranslatedText":"You, baby",
                "Syllables":[{"Text":"君","StartTime":1.0,"EndTime":1.5,"IsPartOfWord":false,"TransliteratedText":"kimi"},
                             {"Text":"baby","StartTime":1.5,"EndTime":2.0,"IsPartOfWord":false}]},
              "Background":[{"StartTime":1.2,"EndTime":1.9,"TranslatedText":"(ooh)","Syllables":[
                 {"Text":"うー","StartTime":1.2,"EndTime":1.9,"IsPartOfWord":false,"TransliteratedText":"uu"}]}]}]}}"""
        val document = provider.fetch(request.copy(spotifyTrackId = "x"))!!

        val lead = document.lines.first { it.role == LineRole.LEAD }
        assertEquals("kimi baby", lead.romanized)
        assertEquals(listOf("kimi", "baby"), lead.syllables.map { it.romanized })
        assertEquals("You, baby", lead.translated)
        val backing = document.lines.first { it.role == LineRole.BACKGROUND }
        assertEquals("うー", backing.text)
        assertEquals("uu", backing.romanized)
        assertEquals(listOf("uu"), backing.syllables.map { it.romanized })
        assertEquals("(ooh)", backing.translated)
    }

    /** A reading the API left out of a non-Latin word is for the on-device romanizer to supply. */
    @Test
    fun `a non-Latin syllable without a reading leaves the line to the romanizer`() = runBlocking {
        server.routes["/v1/lyrics/"] = 200 to """{"Status":200,"Body":{"Type":"Syllable","source":"spicy_lyrics","id":"x","ids":{"spotify":"x"},
            "Content":[{"Type":"Vocal","Lead":{"StartTime":1.0,"EndTime":2.0,
                "Syllables":[{"Text":"音","StartTime":1.0,"EndTime":1.5,"IsPartOfWord":true,"TransliteratedText":"yīn"},
                             {"Text":"乐","StartTime":1.5,"EndTime":2.0,"IsPartOfWord":false}]}}]}}"""
        val lead = provider.fetch(request.copy(spotifyTrackId = "x"))!!.lines.first { it.role == LineRole.LEAD }

        assertNull(lead.romanized)
        assertEquals(listOf(null, null), lead.syllables.map { it.romanized })
    }

    @Test
    fun `without a Spotify id the ISRC is asked for, and without either the title is searched`() = runBlocking {
        server.routes["/v1/lyrics/"] = 200 to community
        provider.fetch(request.copy(isrc = "jpvi02403562"))
        assertEquals("GET /v1/lyrics/JPVI02403562 HTTP/1.1", server.requests.last())

        server.routes["/v1/tracks/search"] = 200 to """{"Status":200,"Body":{"results":[
            {"id":"4uLU6hMCjMI75M1A2tKUQC","ids":{"spotify":"4uLU6hMCjMI75M1A2tKUQC","isrc":"JPVI02403562"},
             "title":"怪獣","artists":["サカナクション"],"duration":252.722,"tier":"exact"}]}}"""
        val learned = ArrayList<String>()
        val document = provider.fetch(request.apply { onIsrc = { learned += it } })

        assertEquals("何度でも", document!!.lines.first { !it.isInterlude }.text)
        val search = server.requests.first { it.startsWith("GET /v1/tracks/search") }
        assertTrue(search, search.contains("title=") && search.contains("artist=") && search.contains("duration=252.722"))
        assertEquals("GET /v1/lyrics/4uLU6hMCjMI75M1A2tKUQC HTTP/1.1", server.requests.last())
        assertEquals(listOf("JPVI02403562", "JPVI02403562"), learned)
    }

    @Test
    fun `a search hit the API does not call exact is not taken`() = runBlocking {
        server.routes["/v1/tracks/search"] = 200 to """{"Status":200,"Body":{"results":[
            {"id":"other","title":"怪獣 (Live)","artists":["サカナクション"],"duration":309.6,"tier":"version"}]}}"""
        assertNull(provider.fetch(request))
        assertEquals(1, server.requests.size)
        assertEquals("Spicy Lyrics' closest match is not this recording", provider.noMatchReason)
    }

    @Test
    fun `a track they do not have is a miss`() = runBlocking {
        assertNull(provider.fetch(request.copy(spotifyTrackId = "x")))
        assertEquals("Spicy Lyrics has no lyrics for this track", provider.noMatchReason)
    }

    /**
     * A miss is remembered for the track; a bad key is not about the track. Reported as a failure,
     * the lookup is retried once the key is fixed, instead of thirty days of silence per song.
     */
    @Test
    fun `a refused key is a failure, by id and by search`() = runBlocking {
        server.routes["/v1/lyrics/"] = 403 to """{"Body":{"error":"forbidden","message":"origin not allowed"}}"""
        try {
            provider.fetch(request.copy(spotifyTrackId = "x"))
            fail("a refusal must not become a settled miss")
        } catch (expected: SpicyLyricsProvider.Refused) {
            assertEquals("Spicy Lyrics refused the key (HTTP 403)", expected.message)
        }

        server.routes["/v1/tracks/search"] = 401 to """{"Body":{"error":"unauthorized"}}"""
        try {
            provider.fetch(request)
            fail("a refusal must not become a settled miss")
        } catch (expected: SpicyLyricsProvider.Refused) {
            assertTrue(expected.message!!.endsWith("(HTTP 401)"))
        }
    }

    @Test
    fun `a rate limit is "not now" rather than "not here"`() = runBlocking {
        server.routes["/v1/lyrics/"] = 429 to """{"Body":{"error":"rate_limited"}}"""
        try {
            provider.fetch(request.copy(spotifyTrackId = "x"))
            fail("a 429 must not be cached as a miss")
        } catch (expected: Http.Unavailable) {
            // The repository leaves the track un-cached and asks again next time.
        }
    }

    @Test
    fun `a secret key is refused before anything is sent`() = runBlocking {
        val secret = SpicyLyricsProvider(Credentials("sl_sk_this_belongs_on_a_server"), server.url)
        assertFalse(secret.isConfigured)
        assertTrue(secret.unavailableReason!!.startsWith("That is a secret key"))
        server.routes["/v1/lyrics/"] = 200 to community
        assertNull(secret.fetch(request.copy(spotifyTrackId = "x")))
        assertTrue(server.requests.isEmpty())

        // Pasted as the whole header, as people do, the key is still recognised.
        assertTrue(SpicyLyricsProvider(Credentials("Authorization: Bearer $key"), server.url).isConfigured)
        assertFalse(SpicyLyricsProvider(Credentials(null), server.url).isConfigured)
        assertNull(SpicyLyricsProvider(Credentials(null), server.url).unavailableReason)
        assertEquals("Not a Spicy Lyrics client key — one begins with sl_pk_.", SpicyLyricsProvider(Credentials("hello"), server.url).unavailableReason)
    }

    @Test
    fun `line-timed and untimed answers keep their shape`() = runBlocking {
        server.routes["/v1/lyrics/"] = 200 to """{"Status":200,"Body":{"Type":"Line","source":"spotify","id":"x","ids":{"spotify":"x"},
            "Content":[{"Text":"First line","StartTime":10.81,"EndTime":12.0,"OppositeAligned":false,"TranslatedText":"Primera línea"},
                       {"Text":"Second line","StartTime":12.0,"EndTime":14.5,"OppositeAligned":false}]}}"""
        val line = provider.fetch(request.copy(spotifyTrackId = "x"))!!
        assertEquals(LyricsKind.LINE, line.kind)
        assertEquals(listOf("First line", "Second line"), line.vocalLines.map { it.text })
        assertEquals(10_810, line.vocalLines.first().startMs)
        assertEquals("Primera línea", line.vocalLines.first().translated)

        server.routes["/v1/lyrics/"] = 200 to """{"Status":200,"Body":{"Type":"Static","source":"apple_music","id":"x","ids":{"spotify":"x"},
            "Lines":[{"Text":"Only words"},{"Text":"No times"}]}}"""
        val static = provider.fetch(request.copy(spotifyTrackId = "x"))!!
        assertEquals(LyricsKind.STATIC, static.kind)
        assertEquals(listOf("Only words", "No times"), static.vocalLines.map { it.text })
    }
}
