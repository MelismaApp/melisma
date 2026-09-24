package com.melisma.app.media

import com.melisma.app.settings.CanvasMode
import com.melisma.app.settings.Settings
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
 * Pinned against `EntityCanvazRequest` / `EntityCanvazResponse` and against the live endpoint
 * (2026-09-23, a pasted web-player token, no client-token): a track with a Canvas answered 200 with
 * the shape below, one without answered 200 with only `ttl_in_seconds`, and no token answered 401.
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
        assertEquals(mapOf("spotify:track:$trackId" to CanvasLink(video)), SpotifyCanvas.canvasLinks(response))
    }

    @Test
    fun `the live response is read`() {
        // Field for field what the endpoint returned for a track with a Canvas, ids shortened.
        val video = "https://canvaz.scdn.co/upload/artist/06HL/video/1650.cnvs.mp4"
        val response = message {
            field(1, message {
                field(1, "1650363f")
                field(2, video)
                field(3, "9fb00cb0")
                varint(4, 3) // VIDEO_LOOPING_RANDOM
                field(5, "spotify:track:$trackId")
                field(6, message {
                    field(1, "spotify:artist:06HL")
                    field(2, "Taylor Swift")
                    field(3, "https://i.scdn.co/image/ab67")
                })
                field(8, "artist")
                field(11, "spotify:canvas:0G6u")
                // Stills, not videos: height first, then width, then the image.
                field(13, message { varint(1, 256); varint(2, 144); field(3, "https://i.scdn.co/image/ab67ba6900002ea6") })
                field(13, message { varint(1, 512); varint(2, 288); field(3, "https://i.scdn.co/image/ab67ba6900002e9f") })
            })
            varint(2, 3600)
        }
        val link = CanvasLink(video, posterUrl = "https://i.scdn.co/image/ab67ba6900002e9f")
        assertEquals(mapOf("spotify:track:$trackId" to link), SpotifyCanvas.canvasLinks(response))
    }

    @Test
    fun `a track without a canvas is answered with only a ttl`() {
        assertTrue(SpotifyCanvas.canvasLinks(message { varint(2, 3600) }).isEmpty())
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
        assertTrue(SpotifyCanvas.canvasLinks(response).isEmpty())
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
        assertEquals(video, SpotifyCanvas.canvasLinks(response)["spotify:track:$trackId"]?.videoUrl)
    }

    @Test
    fun `a truncated or garbled body yields nothing rather than throwing`() {
        val good = message {
            field(1, message {
                field(2, "https://canvaz.scdn.co/upload/x.mp4")
                field(5, "spotify:track:$trackId")
            })
        }
        for (cut in 1 until good.size) SpotifyCanvas.canvasLinks(good.copyOf(cut))
        assertTrue(SpotifyCanvas.canvasLinks(byteArrayOf(0x0f, 0x7f, 0x7f)).isEmpty())
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
    fun `only https images on Spotify's CDN count as stills`() {
        assertTrue(SpotifyCanvas.isCanvasStill("https://i.scdn.co/image/ab67ba6900002e9f"))
        assertFalse(SpotifyCanvas.isCanvasStill("http://i.scdn.co/image/ab67"))
        assertFalse(SpotifyCanvas.isCanvasStill("https://i.scdn.co.example.com/image/ab67"))
        assertFalse(SpotifyCanvas.isCanvasStill("https://canvaz.scdn.co/upload/x.mp4"))
    }

    @Test
    fun `a still that is not an image is not a poster`() {
        val video = "https://canvaz.scdn.co/upload/x.mp4"
        val response = message {
            field(1, message {
                field(2, video)
                field(5, "spotify:track:$trackId")
                field(13, message { varint(1, 512); varint(2, 288); field(3, "https://example.com/x.jpg") })
            })
        }
        assertEquals(CanvasLink(video), SpotifyCanvas.canvasLinks(response)["spotify:track:$trackId"])
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

    @Test
    fun `nothing is fetched that nothing on screen would show`() {
        val full = Settings(canvasMode = CanvasMode.FULL)
        val blurred = Settings(canvasMode = CanvasMode.BLURRED)
        fun showable(s: Settings, window: Boolean = false, popup: Boolean = false, car: Boolean = false, blur: Boolean = true) =
            canvasShowable(s, window = window, popup = popup, car = car, blurAvailable = blur)

        // The app closed with music playing: the case that downloaded a video per track.
        assertFalse(showable(full))
        assertTrue(showable(full, window = true))
        assertFalse(showable(Settings(canvasMode = CanvasMode.OFF), window = true, car = true))

        // A floating window shows one only when its background is allowed to move.
        assertFalse(showable(full, window = true, popup = true))
        assertTrue(showable(full.copy(popupStillBackground = false), window = true, popup = true))

        // The car plays Blurred only, and only with the render effect to blur it.
        assertFalse(showable(full, car = true))
        assertTrue(showable(blurred, car = true))
        assertFalse(showable(blurred, car = true, blur = false))
    }

    @Test
    fun `the savers allow a still or nothing, as each is set`() {
        fun plan(held: Boolean = false, heldStill: Boolean = true, dataSaver: Boolean = false, dataSaverStill: Boolean = false) =
            canvasPlan(held, heldStill, dataSaver, dataSaverStill)

        assertEquals(CanvasPlan.VIDEO, plan())
        // A switch that only applies under a saver changes nothing without one.
        assertEquals(CanvasPlan.VIDEO, plan(heldStill = false, dataSaverStill = true))

        // The defaults: battery saver shows the still, Data Saver skips Canvas.
        assertEquals(CanvasPlan.STILL, plan(held = true))
        assertEquals(CanvasPlan.NONE, plan(dataSaver = true))

        assertEquals(CanvasPlan.NONE, plan(held = true, heldStill = false))
        assertEquals(CanvasPlan.STILL, plan(dataSaver = true, dataSaverStill = true))

        // Both on: either one saying no is no.
        assertEquals(CanvasPlan.NONE, plan(held = true, dataSaver = true))
        assertEquals(CanvasPlan.NONE, plan(held = true, heldStill = false, dataSaver = true, dataSaverStill = true))
        assertEquals(CanvasPlan.STILL, plan(held = true, dataSaver = true, dataSaverStill = true))
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
