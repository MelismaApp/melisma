package com.melisma.app.lyrics.romanize

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
import com.melisma.app.util.Script
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pinyin by word rather than by character.
 *
 * Reported as "的 can be read de or dí or dì or dé or děi, I don't think we can fix that" — and the
 * measurement said the reported case is the middle of the problem rather than its edge. ICU gives each
 * character one fixed reading, so of 264,677 words containing a character with more than one reading
 * it is wrong for 31,710, including ordinary ones: 音乐 as `yin le`, 了解 as `le jie`, 地方 as `de fang`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinyinWordsTest {

    private val romanizer = Romanizer()

    @Test
    fun `the table shipped and loaded`() {
        assertTrue("the resource should be on the classpath", PinyinWords.isAvailable)
    }

    @Test
    fun `a word ICU reads wrongly is read correctly`() {
        // The one I quoted back: 乐 is lè in 快乐 and yuè in 音乐, and ICU says lè in both.
        assertEquals("yīn yuè", romanizer.romanizeText("音乐", Script.CHINESE))
        assertEquals("kuài lè", romanizer.romanizeText("快乐", Script.CHINESE))
    }

    @Test
    fun `and the reported characters get their contextual readings`() {
        assertEquals("dì fāng", romanizer.romanizeText("地方", Script.CHINESE))
        assertEquals("mù dì", romanizer.romanizeText("目的", Script.CHINESE))
        assertEquals("jué de", romanizer.romanizeText("觉得", Script.CHINESE))
        assertEquals("liǎo jiě", romanizer.romanizeText("了解", Script.CHINESE))
    }

    @Test
    fun `tone sandhi comes out of the same lookup`() {
        // Rules rather than exceptions, but a per-character table cannot express them either.
        assertEquals("bú yào", romanizer.romanizeText("不要", Script.CHINESE))
        assertEquals("yì qǐ", romanizer.romanizeText("一起", Script.CHINESE))
    }

    @Test
    fun `words ICU already reads correctly are untouched`() {
        // The table holds only the corrections, so most of a line still comes from ICU.
        assertEquals("wǒ men", romanizer.romanizeText("我们", Script.CHINESE))
        assertEquals("shì jiè", romanizer.romanizeText("世界", Script.CHINESE))
    }

    @Test
    fun `a whole line mixes the table and ICU without losing a character`() {
        val line = "我们的音乐世界"
        val reading = romanizer.romanizeText(line, Script.CHINESE)!!
        // One reading per character, in order, whichever source each came from.
        assertEquals(line.length, reading.split(' ').size)
        assertTrue(reading, reading.contains("yuè"))
        assertTrue(reading, reading.startsWith("wǒ men"))
    }

    @Test
    fun `dropping tone marks still applies to a table reading`() {
        val reading = romanizer.romanizeText("音乐", Script.CHINESE, stripDiacritics = true)
        assertEquals("yin yue", reading)
    }

    @Test
    fun `a word spanning two syllables is read as that word`() = runBlocking {
        // The case that makes the table reachable at all. Each syllable is romanized on its own
        // today, so 音 and 乐 arrive separately and neither of them is 音乐.
        val document = LyricsDocument(
            kind = LyricsKind.SYLLABLE,
            lines = listOf(
                LyricLine(
                    role = LineRole.LEAD,
                    startMs = 0,
                    endMs = 2_000,
                    text = "音乐",
                    syllables = listOf(
                        Syllable("音", 0, 1_000),
                        Syllable("乐", 1_000, 2_000),
                    ),
                ),
            ),
            providerName = "test",
            providerId = "test",
        )

        val out = romanizer.annotate(document, romanize = true, furigana = false)
        val syllables = out.lines[0].syllables
        assertEquals("yīn", syllables[0].romanized)
        assertEquals("yuè", syllables[1].romanized)
    }

    @Test
    fun `a surrogate pair survives a line that also has a table match`() {
        // Stepping through UTF-16 units read each half of an emoji separately and pushed a separator
        // between them, turning one character into two broken ones. Only reachable when something
        // else in the run matched the table, which is what makes it easy to miss.
        val note = "🎵"
        val reading = romanizer.romanizeText("音乐" + note, Script.CHINESE)!!
        assertTrue(reading, reading.contains("yuè"))
        assertTrue("the pair must arrive whole: $reading", reading.contains(note))
    }

    @Test
    fun `and so does a supplementary ideograph`() {
        // CJK Extension B: a real character, outside the range any of this has readings for.
        val extB = "𠀋"
        val reading = romanizer.romanizeText("音乐" + extB, Script.CHINESE)!!
        assertTrue("the pair must arrive whole: $reading", reading.contains(extB))
    }

    @Test
    fun `an unmatched character still gets a reading`() {
        // A stretch no word covered falls through to ICU rather than coming out blank.
        val reading = romanizer.romanizeText("錒音乐", Script.CHINESE)
        assertTrue(reading, !reading.isNullOrBlank() && reading.contains("yuè"))
    }
}
