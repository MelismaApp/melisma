package com.melisma.app.media

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotify Canvas: the request, the response, and what may be downloaded on the strength of one.
 *
 * The protocol could not be exercised end to end without a live token, so it is pinned here
 * against its definition — `EntityCanvazRequest` / `EntityCanvazResponse` — and against how the
 * endpoint answered without one: `401`, which is "bring a token", not "no such thing".
 */
class SpotifyCanvasTest {

    private val trackId = "4cOdK2wGLETKBW3PvgPWqT"

    @Test
    fun `the request is one entity, by track URI`() {
        val uri = "spotify:track:$trackId".toByteArray()
        val expected = byteArrayOf(0x0a, (uri.size + 2).toByte(), 0x0a, uri.size.toByte()) + uri
        assertArrayEquals(expected, SpotifyCanvas.canvasRequest(trackId))
    }

    @Test
    fun `a video canvas is found, keyed by its track`() {
        val video = "https://canvaz.scdn.co/upload/artist/abc/video/def.cnvs.mp4"
        val response = message {
            field(1, message {
                field(1, "id-1")
                field(2, video)
                varint(4, 2) // VIDEO_LOOPING
                field(5, "spotify:track:$trackId")
            })
        }
        assertEquals(mapOf("spotify:track:$trackId" to video), SpotifyCanvas.canvasUrls(response))
    }

    @Test
    fun `an image canvas and a foreign URL are both dropped`() {
        val response = message {
            field(1, message {
                field(2, "https://canvaz.scdn.co/upload/artist/abc/image/def.jpg")
                varint(4, 0) // IMAGE
                field(5, "spotify:track:one")
            })
            field(1, message {
                field(2, "https://example.com/not-spotify.mp4")
                field(5, "spotify:track:two")
            })
        }
        assertTrue(SpotifyCanvas.canvasUrls(response).isEmpty())
    }

    @Test
    fun `fields it does not read are skipped rather than derailing it`() {
        // Every wire type Spotify might add, around the two fields that matter.
        val video = "https://canvaz.scdn.co/upload/x.mp4"
        val response = message {
            varint(2, 3600) // ttl_in_seconds
            field(1, message {
                varint(3, 7)
                fixed64(8)
                field(2, video)
                fixed32(9)
                field(6, message { field(1, "artist") })
                field(5, "spotify:track:$trackId")
            })
        }
        assertEquals(video, SpotifyCanvas.canvasUrls(response)["spotify:track:$trackId"])
    }

    @Test
    fun `a truncated or garbled body yields nothing rather than throwing`() {
        val good = message {
            field(1, message {
                field(2, "https://canvaz.scdn.co/upload/x.mp4")
                field(5, "spotify:track:$trackId")
            })
        }
        for (cut in 1 until good.size) SpotifyCanvas.canvasUrls(good.copyOf(cut))
        assertTrue(SpotifyCanvas.canvasUrls(byteArrayOf(0x0f, 0x7f, 0x7f)).isEmpty())
    }

    @Test
    fun `only https videos on Spotify's CDN count`() {
        assertTrue(SpotifyCanvas.isCanvasVideo("https://canvaz.scdn.co/upload/x.mp4"))
        assertFalse(SpotifyCanvas.isCanvasVideo("http://canvaz.scdn.co/upload/x.mp4"))
        assertFalse(SpotifyCanvas.isCanvasVideo("https://canvaz.scdn.co.example.com/x.mp4"))
        assertFalse(SpotifyCanvas.isCanvasVideo("https://canvaz.scdn.co/upload/x.jpg"))
        assertFalse(SpotifyCanvas.isCanvasVideo("not a url"))
    }

    @Test
    fun `the cache refuses to download anything that is not a canvas`() = runBlocking {
        val dir = Files.createTempDirectory("canvas").toFile()
        try {
            assertNull(CanvasVideoCache(dir).fileFor("https://example.com/video.mp4"))
            assertTrue(dir.listFiles().isNullOrEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- a minimal protobuf writer, independent of the one under test ----

    private class Writer {
        val out = ByteArrayOutputStream()
        private fun raw(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
        fun field(number: Int, bytes: ByteArray) { raw((number shl 3 or 2).toLong()); raw(bytes.size.toLong()); out.write(bytes) }
        fun field(number: Int, text: String) = field(number, text.toByteArray())
        fun varint(number: Int, value: Long) { raw((number shl 3).toLong()); raw(value) }
        fun fixed64(number: Int) { raw((number shl 3 or 1).toLong()); out.write(ByteArray(8)) }
        fun fixed32(number: Int) { raw((number shl 3 or 5).toLong()); out.write(ByteArray(4)) }
    }

    private fun message(build: Writer.() -> Unit): ByteArray = Writer().apply(build).out.toByteArray()
}
