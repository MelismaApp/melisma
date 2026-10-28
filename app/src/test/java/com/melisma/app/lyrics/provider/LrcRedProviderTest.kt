package com.melisma.app.lyrics.provider

import com.melisma.app.lyrics.model.LyricsKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LrcRedProviderTest {

    /** Answers by path, recording each request line. */
    private class Server {
        val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val routes = HashMap<String, Pair<Int, String>>()
        val requests = ConcurrentLinkedQueue<String>()

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
                        }
                        requests += requestLine
                        val path = requestLine.split(' ').getOrNull(1).orEmpty()
                        val (status, body) = routes.entries.firstOrNull { path.startsWith(it.key) }?.value ?: (404 to "")
                        val bytes = body.toByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $status X\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }

        val url get() = "http://127.0.0.1:${socket.localPort}"
    }

    private val server = Server()
    private val provider = LrcRedProvider(server.url)

    @After
    fun tearDown() = server.socket.close()

    /** lrc.red's TTML as served: Apple's shape under the lrc namespace, with a translation and a reading. */
    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" lrc:timing="Word" xml:lang="ja"><head><metadata><ttm:agent type="person" xml:id="v1"/><sourceMetadata xmlns="http://lrc.red/lyric-ttml-internal" leadingSilence="0.100"><translations><translation type="subtitle" xml:lang="en-US"><text for="L1">Over and over again</text></translation></translations><transliterations><transliteration xml:lang="ja-Latn"><text for="L1">nando de mo</text></transliteration></transliterations></sourceMetadata></metadata></head><body dur="4:12.722"><div begin="1.133" end="2.979" lrc:songPart="Intro"><p begin="1.133" end="2.979" lrc:key="L1" ttm:agent="v1"><span begin="1.133" end="1.757">何度</span><span begin="1.757" end="2.200">で</span><span begin="2.200" end="2.979">も</span></p></div></body></tt>"""

    private val request = LyricsRequest(title = "怪獣", artist = "サカナクション", album = "怪獣", durationMs = 252_722)

    @Test
    fun `an ISRC is one exact request, and Apple's data comes through whole`() = runBlocking {
        server.routes["/s/JPVI02403562.ttml"] = 200 to ttml
        val document = provider.fetch(request.copy(isrc = "jpvi02403562"))!!

        assertEquals(LyricsKind.SYLLABLE, document.kind)
        assertEquals("lrc.red", document.providerName)
        val line = document.lines.first { !it.isInterlude }
        assertEquals("何度でも", line.text)
        assertEquals("Over and over again", line.translated)
        // Apple writes the reading for the whole line, as lrc.red does; it is shown under the line.
        assertEquals("nando de mo", line.romanized)
        assertEquals(listOf("何度", "で", "も"), line.syllables.map { it.text })
        assertEquals(listOf("GET /s/JPVI02403562.ttml HTTP/1.1"), server.requests.toList())
    }

    @Test
    fun `without an ISRC the song is searched for, and the match's ISRC is reported`() = runBlocking {
        server.routes["/search.json"] = 200 to """{"hits":[
            {"isrc":"JPVI02403570","title":"怪獣 (Live)","artist":"サカナクション","album":"Live","duration":309.6},
            {"isrc":"JPVI02403562","title":"怪獣","artist":"サカナクション","album":"怪獣","duration":252.722}
        ],"next":null}"""
        server.routes["/s/JPVI02403562.ttml"] = 200 to ttml
        val learned = ArrayList<String>()
        val document = provider.fetch(request.apply { onIsrc = { learned += it } })

        assertEquals("何度でも", document!!.lines.first { !it.isInterlude }.text)
        assertEquals(listOf("JPVI02403562"), learned)
        assertTrue(server.requests.first(), server.requests.first().startsWith("GET /search.json?q="))
    }

    @Test
    fun `a song they do not have is a miss, by ISRC and by search`() = runBlocking {
        server.routes["/search.json"] = 200 to """{"hits":[],"next":null}"""
        assertNull(provider.fetch(request.copy(isrc = "USUG11904206")))
        // The ISRC missed, so the search was tried before giving up.
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `a hit for another song is not taken`() = runBlocking {
        server.routes["/search.json"] = 200 to """{"hits":[{"isrc":"X","title":"Something Else","artist":"Nobody","duration":100}]}"""
        assertNull(provider.fetch(request))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `the namespace is renamed to Apple's, attributes included, and nothing else`() {
        val apple = LrcRedProvider.appleTtml(ttml)
        assertTrue(apple.contains("xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\""))
        assertTrue(apple.contains("itunes:timing=\"Word\"") && apple.contains("itunes:key=\"L1\""))
        assertTrue(!apple.contains("lrc:"))
        // Text is untouched: a lyric containing "lrc:" in prose is not an attribute.
        assertTrue(LrcRedProvider.appleTtml("<p>see lrc:red</p>").contains("lrc:red"))
    }
}
