package com.melisma.app.media

import com.melisma.app.lyrics.provider.ProviderCredentials
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the server's extras answers are remembered as.
 *
 * An outage used to be cached as "the server has nothing for this track", so a track looked up
 * during a blip, or while being skipped, never got its extras or its Canvas from the server again
 * for as long as the app ran, and changing the server did not clear it.
 */
class CacheServerExtrasCacheTest {

    /** Answers each request with the next queued status and body, closing every connection. */
    private class Responder {
        val socket = ServerSocket(0)
        val replies = ConcurrentLinkedQueue<Pair<Int, String>>()
        val requests = AtomicInteger()

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use {
                        val reader = it.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        requests.incrementAndGet()
                        val (status, body) = replies.poll() ?: (404 to "")
                        val bytes = body.toByteArray()
                        it.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 $status X\r\nContent-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                            )
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }

        val url get() = "http://127.0.0.1:${socket.localPort}"
    }

    private class Creds(override var cacheServerUrl: String?) : ProviderCredentials {
        override val lrcLibBaseUrl = ""
        override val neteaseBaseUrl = ""
        override val amllBaseUrl = ""
        override val cacheServerKey: String? = null
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
    private val track = TrackInfo(title = "Anti-Hero", artist = "Taylor Swift", album = "Midnights", durationMs = 200_690)

    @After
    fun tearDown() = server.socket.close()

    @Test
    fun `an outage is not remembered, so the next play asks again`() = runBlocking {
        val extras = CacheServerExtras(Creds(server.url))
        server.replies += 503 to ""
        server.replies += 200 to """{"tempo":97.0}"""

        assertNull(extras.fetch(track))
        assertEquals(97f, extras.fetch(track)?.tempo)
        assertEquals(2, server.requests.get())
    }

    @Test
    fun `a real absence is remembered, so it is asked once`() = runBlocking {
        val extras = CacheServerExtras(Creds(server.url))
        server.replies += 404 to ""

        assertNull(extras.fetch(track))
        assertNull(extras.fetch(track))
        assertEquals(1, server.requests.get())
    }

    @Test
    fun `another server is asked afresh`() = runBlocking {
        val creds = Creds(server.url)
        val extras = CacheServerExtras(creds)
        server.replies += 404 to ""
        assertNull(extras.fetch(track))

        val other = Responder()
        try {
            other.replies += 200 to """{"tempo":120.0}"""
            creds.cacheServerUrl = other.url
            assertEquals(120f, extras.fetch(track)?.tempo)
        } finally {
            other.socket.close()
        }
    }
}
