package com.melisma.app.ui.lyrics

import android.graphics.Bitmap
import android.graphics.Canvas
import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** Reading freely: the page stays where the reader puts it while the song goes on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeReadTest {

    private val metrics = LyricsMetrics(fontSizePx = 20f, simpleMode = false, showSecondaryLine = false)
    private val canvas = Canvas(Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888))

    private fun song(prefix: String = "Line") = LyricsDocument(
        kind = LyricsKind.LINE,
        lines = List(40) { i ->
            LyricLine(role = LineRole.LEAD, startMs = i * 2_000, endMs = i * 2_000 + 1_900, text = "$prefix $i")
        },
        providerName = "test",
        providerId = "test",
    )

    private fun layout(document: LyricsDocument = song()) = LyricsLayoutBuilder.build(
        document = document, metrics = metrics, widthPx = 400f,
        useRomanization = false, showTranslation = false,
    )

    private fun renderer(freeRead: Boolean) = LyricsRenderer(layout()).apply {
        viewportHeight = 600f
        this.freeRead = freeRead
    }

    private var clock = 1_000_000_000L

    /** Plays from [fromMs] to [toMs], a frame every 16 ms. */
    private fun LyricsRenderer.play(fromMs: Int, toMs: Int) {
        var at = fromMs
        while (at <= toMs) {
            clock += 16_000_000L
            draw(canvas, at, clock)
            at += 16
        }
    }

    @Test
    fun `the page does not follow the song`() {
        val following = renderer(freeRead = false).apply { play(0, 1_000) }
        val reading = renderer(freeRead = true).apply { play(0, 1_000) }
        val followStart = following.scrollY
        val readStart = reading.scrollY
        following.play(1_000, 30_000)
        reading.play(1_000, 30_000)
        assertTrue("following moved on", following.scrollY > followStart + 100f)
        assertEquals(readStart, reading.scrollY, 0.01f)
    }

    @Test
    fun `asking for the playing line still brings it into view`() {
        val reading = renderer(freeRead = true).apply { play(0, 30_000) }
        val before = reading.scrollY
        reading.jumpToActive()
        reading.play(30_000, 30_100)
        assertTrue(reading.scrollY > before + 100f)
    }

    @Test
    fun `turning it off glides back to the song`() {
        val reading = renderer(freeRead = true).apply { play(0, 30_000) }
        val left = reading.scrollY
        reading.freeRead = false
        reading.play(30_000, 30_016)
        val firstFrame = reading.scrollY
        reading.play(30_016, 33_000)
        // One frame moves some of the way, not all of it: a glide, not a jump.
        assertTrue(firstFrame > left && firstFrame < reading.scrollY)
    }

    @Test
    fun `the same lines laid out again keep the reader's place`() {
        val reading = renderer(freeRead = true).apply { play(0, 1_000) }
        reading.onDragStart()
        reading.onDrag(-400f)
        reading.onDragEnd(0f, 1_200)
        reading.play(1_000, 1_100)
        val at = reading.scrollY
        // Romanization or a translation toggled: a new document with the same lines.
        reading.layout = layout(song(prefix = "Row"))
        reading.play(1_100, 1_200)
        assertTrue(abs(reading.scrollY - at) < metrics.lineHeightPx * 2)
        // A different song starts where it is playing.
        reading.layout = layout(song().copy(lines = song().lines.drop(1)))
        reading.play(1_200, 1_300)
        assertTrue(abs(reading.scrollY - at) > metrics.lineHeightPx * 2)
    }

    @Test
    fun `a paused page settles after a drag`() {
        val reading = renderer(freeRead = true).apply { play(0, 500) }
        reading.onDragStart()
        reading.onDrag(-50f)
        reading.onDragEnd(0f, 1_200)
        repeat(90) { clock += 16_000_000L; reading.draw(canvas, 500, clock) }
        assertFalse(reading.isSettling)
    }

    @Test
    fun `unsynced lyrics start at the top and settle, free reading or not`() {
        val unsynced = song().copy(
            kind = LyricsKind.STATIC,
            lines = song().lines.map { it.copy(startMs = 0, endMs = 0) },
        )
        for (freeRead in listOf(false, true)) {
            val page = LyricsRenderer(layout(unsynced)).apply {
                viewportHeight = 600f
                this.freeRead = freeRead
            }
            repeat(90) { clock += 16_000_000L; page.draw(canvas, 5_000, clock) }
            assertEquals(0f, page.scrollY, 0.01f)
            assertFalse(page.isSettling)
        }
    }
}
