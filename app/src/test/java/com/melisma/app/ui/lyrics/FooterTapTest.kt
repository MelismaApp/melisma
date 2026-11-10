package com.melisma.app.ui.lyrics

import com.melisma.app.lyrics.model.Credit
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

/** The footer under the last line is where a tap leads to the source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FooterTapTest {

    private fun layout(credits: Boolean) = LyricsLayoutBuilder.build(
        document = LyricsDocument(
            kind = LyricsKind.LINE,
            lines = List(3) { LyricLine(LineRole.LEAD, it * 1000, it * 1000 + 900, "line $it") },
            providerName = "lrc.red",
            providerId = "lrcred",
            songWriters = listOf("Someone"),
        ),
        metrics = LyricsMetrics(fontSizePx = 10f, simpleMode = false, showSecondaryLine = false),
        widthPx = 400f,
        useRomanization = false,
        showTranslation = false,
        showCredits = credits,
    )

    @Test
    fun `the footer answers for its own rows and nothing above them`() {
        val with = layout(credits = true)
        val rows = with.footer
        assertTrue(rows.size >= 2)
        assertTrue(with.footerAt(rows.first().baseline))
        assertTrue(with.footerAt(rows.last().baseline))
        // The last lyric line is not the footer.
        assertFalse(with.footerAt(with.lines.last().top + 1f))
        assertFalse(layout(credits = false).footerAt(rows.last().baseline))
    }

    @Test
    fun `credited people get rows of their own, and a tap on one leads to them`() {
        val layout = LyricsLayoutBuilder.build(
            document = LyricsDocument(
                kind = LyricsKind.LINE,
                lines = List(2) { LyricLine(LineRole.LEAD, it * 1000, it * 1000 + 900, "line $it") },
                providerName = "Spicy Lyrics",
                providerId = "spicylyrics",
                credits = listOf(
                    Credit("Made by gc", "https://spicylyrics.org/uid/2"),
                    Credit("Uploaded by spikerko", "https://spicylyrics.org/uid/1"),
                ),
            ),
            metrics = LyricsMetrics(fontSizePx = 10f, simpleMode = false, showSecondaryLine = false),
            widthPx = 400f,
            useRomanization = false,
            showTranslation = false,
            showCredits = true,
        )
        val rows = layout.footer
        assertEquals(listOf("Lyrics from Spicy Lyrics", "Made by gc", "Uploaded by spikerko"), rows.map { it.text })
        // The source's row has no link of its own; the caller knows where the source lives.
        assertEquals(listOf(null, "https://spicylyrics.org/uid/2", "https://spicylyrics.org/uid/1"), rows.map { it.url })
        assertEquals("Made by gc", layout.footerRowAt(rows[1].baseline)!!.text)
        assertEquals("Uploaded by spikerko", layout.footerRowAt(rows[2].baseline)!!.text)
        assertEquals(null, layout.footerRowAt(rows[0].baseline)!!.url)
        assertEquals(null, layout.footerRowAt(layout.lines.last().top + 1f))
    }
}
