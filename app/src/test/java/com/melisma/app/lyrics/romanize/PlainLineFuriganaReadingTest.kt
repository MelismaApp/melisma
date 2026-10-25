package com.melisma.app.lyrics.romanize

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A line-synced Japanese line gets its readings against the text, since it has no syllables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlainLineFuriganaReadingTest {

    private fun document(text: String) = LyricsDocument(
        kind = LyricsKind.LINE,
        lines = listOf(LyricLine(role = LineRole.LEAD, startMs = 0, endMs = 3_000, text = text)),
        providerName = "test",
        providerId = "test",
    )

    @Test
    fun `kanji get readings, kana do not, and the text is untouched`() = runBlocking {
        val line = Romanizer().annotate(document("夜に駆ける"), romanize = false, furigana = true).lines[0]
        assertEquals("夜に駆ける", line.text)
        val readings = line.furigana.map { line.text.substring(it.start, it.end) to it.kana }
        assertTrue(readings.toString(), readings.any { (over, kana) -> over == "夜" && kana == "ヨル" })
        assertTrue(readings.toString(), readings.any { (over, kana) -> over.startsWith("駆") && kana.startsWith("カ") })
        assertTrue(readings.none { (over, _) -> over == "に" })
    }

    @Test
    fun `a line in another script is left alone`() = runBlocking {
        val line = Romanizer().annotate(document("Hello world"), romanize = false, furigana = true).lines[0]
        assertTrue(line.furigana.isEmpty())
    }
}
