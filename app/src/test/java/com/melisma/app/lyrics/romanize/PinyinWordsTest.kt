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

    @Test
    fun `Traditional lyrics are read by word too`() {
        // The table is Simplified; until it was looked up folded, these came out yīn lè and yín xíng.
        assertEquals("yīn yuè", romanizer.romanizeText("音樂", Script.CHINESE))
        assertEquals("yín háng", romanizer.romanizeText("銀行", Script.CHINESE))
    }

    @Test
    fun `a particle is not taken by a rare word beside it`() {
        // Reported: 的真, "genuine", read this as dí zhēn.
        assertTrue(romanizer.romanizeText("愛你的真心", Script.CHINESE)!!.endsWith("nǐ de zhēn xīn"))
        assertEquals("wǒ de dāng xià", romanizer.romanizeText("我的當下", Script.CHINESE))
        assertTrue(romanizer.romanizeText("說明了一切", Script.CHINESE)!!.startsWith("shuō míng le"))
        assertEquals("qiān zhe shǒu", romanizer.romanizeText("牽著手", Script.CHINESE))
        assertEquals("máng mù de ài", romanizer.romanizeText("盲目的愛", Script.CHINESE))
        // The common words are still read as words, and do not take from theirs.
        assertEquals("zhí zhuó", romanizer.romanizeText("執著", Script.CHINESE))
        assertEquals("wèi le jiě jué", romanizer.romanizeText("為了解決", Script.CHINESE))
    }

    /** A song's worth of ordinary syllables, 300 ms each, so it has a typical length to compare with. */
    private fun song(vararg lines: LyricLine) = LyricsDocument(
        kind = LyricsKind.SYLLABLE, providerName = "Test", providerId = "test",
        lines = lines.toList() + List(6) { n -> line(n * 10_000 + 60_000, "我" to 300, "們" to 300, "走" to 300, "吧" to 300) },
    )

    private fun line(start: Int, vararg parts: Pair<String, Int>): LyricLine {
        var at = start
        val syllables = parts.map { (text, length) -> Syllable(text = text, startMs = at, endMs = at + length).also { at += length } }
        return LyricLine(role = LineRole.LEAD, startMs = start, endMs = at, text = parts.joinToString("") { it.first }, syllables = syllables)
    }

    @Test
    fun `a held 了 is sung liao`() = runBlocking {
        val held = line(0, "過" to 300, "了" to 1_200, "海" to 400)
        val passing = line(5_000, "過" to 300, "了" to 250, "海" to 400)
        val word = line(10_000, "了" to 1_200, "解" to 300)
        val lines = romanizer.annotate(song(held, passing, word), romanize = true, furigana = false).lines
        assertEquals("guò liǎo hǎi", lines[0].romanized)
        assertEquals("guò le hǎi", lines[1].romanized)
        // Already liǎo, in 了解.
        assertEquals("liǎo jiě", lines[2].romanized)
        val stripped = romanizer.annotate(song(held), romanize = true, furigana = false, stripDiacritics = true).lines
        assertEquals("guo liao hai", stripped[0].romanized)
    }

    @Test
    fun `punctuation after a held 了 does not hide it`() = runBlocking {
        val lines = romanizer.annotate(song(line(0, "走" to 300, "了！" to 1_200)), romanize = true, furigana = false).lines
        assertTrue(lines[0].syllables[1].romanized!!.startsWith("liǎo"))
    }

    @Test
    fun `a 了 whose end was only inferred is not taken as held`() = runBlocking {
        // Enhanced LRC where every word is closed but the last, 過了, which runs to the next line four
        // seconds on.
        val closed = (0 until 8).joinToString("\n") { n ->
            val s = n * 10 + 5
            "[00:$s.00]<00:$s.00>我<00:$s.30><00:$s.30>們<00:$s.60><00:$s.60>走<00:$s.90><00:$s.90>吧<00:${s + 1}.20>"
        }
        val lrc = closed + "\n[01:30.00]<01:30.00>過了\n[01:34.00]<01:34.00>海<01:34.30>"
        val document = com.melisma.app.lyrics.parse.LrcParser.toDocument(lrc, "Test", "test")!!
        val line = romanizer.annotate(document, romanize = true, furigana = false).lines.first { "了" in it.text }
        assertEquals("guò le", line.syllables.last().romanized)
    }

    @Test
    fun `a word whose key folds differently is still found`() {
        // 著 folds to 着 for the lookup; 名著 and 著作 had become unreachable, and ICU reads them zhe.
        assertEquals("míng zhù", romanizer.romanizeText("名著", Script.CHINESE))
        assertEquals("zhù zuò", romanizer.romanizeText("著作", Script.CHINESE))
    }
}
