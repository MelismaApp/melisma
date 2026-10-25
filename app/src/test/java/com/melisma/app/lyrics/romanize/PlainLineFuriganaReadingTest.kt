package com.melisma.app.lyrics.romanize

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
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

    @Test
    fun `a line timed as one block gets readings against its text, not one over the whole line`() = runBlocking {
        val oneBlock = LyricsDocument(
            kind = LyricsKind.SYLLABLE,
            lines = listOf(
                LyricLine(
                    role = LineRole.LEAD, startMs = 0, endMs = 3_000, text = "夜に駆ける",
                    syllables = listOf(Syllable("夜に駆ける", 0, 3_000)),
                ),
            ),
            providerName = "test",
            providerId = "test",
        )
        val line = Romanizer().annotate(oneBlock, romanize = false, furigana = true).lines[0]
        assertTrue(line.furigana.toString(), line.furigana.size >= 2)
        assertTrue(line.syllables.all { it.kana == null })

        // A line with real syllables keeps its readings on them.
        val timed = oneBlock.copy(
            lines = listOf(
                oneBlock.lines[0].copy(
                    syllables = listOf(Syllable("夜に", 0, 1_500), Syllable("駆ける", 1_500, 3_000, partOfWord = true)),
                ),
            ),
        )
        val worded = Romanizer().annotate(timed, romanize = false, furigana = true).lines[0]
        assertTrue(worded.furigana.isEmpty())
        assertEquals("ヨルニ", worded.syllables[0].kana)
    }
}
