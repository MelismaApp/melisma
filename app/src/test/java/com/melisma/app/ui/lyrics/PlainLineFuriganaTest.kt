package com.melisma.app.ui.lyrics

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Ruby
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Furigana on a line that has no syllables — line-synced or unsynced lyrics, which is most of what
 * LRCLIB and Spotify return. The readings sit against offsets into the text, and the layout has to
 * place each one over its characters without ever splitting the two across rows.
 *
 * Robolectric measures one unit per character, so widths here are character counts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlainLineFuriganaTest {

    /** 夜に駆ける with readings over 夜 and 駆, as the analyser would give them. */
    private fun line(text: String = "夜に駆ける", rubies: List<Ruby> = listOf(Ruby(0, 1, "ヨル"), Ruby(2, 3, "カ"))) =
        LyricLine(role = LineRole.LEAD, startMs = 1_000, endMs = 4_000, text = text, furigana = rubies)

    private fun layout(
        line: LyricLine = line(),
        kind: LyricsKind = LyricsKind.LINE,
        furigana: Boolean? = true,
        romanize: Boolean = false,
        widthPx: Float = 400f,
    ) = LyricsLayoutBuilder.build(
        document = LyricsDocument(kind = kind, lines = listOf(line), providerName = "test", providerId = "test"),
        metrics = LyricsMetrics(fontSizePx = 10f, simpleMode = false, showSecondaryLine = false),
        widthPx = widthPx,
        useRomanization = romanize,
        showTranslation = false,
        showCredits = false,
        furiganaHiragana = furigana,
    ).lines[0]

    @Test
    fun `each reading sits over its own characters, in hiragana`() {
        val units = layout().units
        assertEquals(listOf("夜", "に", "駆", "ける"), units.map { it.text })
        assertEquals(listOf("よる", null, "か", null), units.map { it.ruby })
        // Centred over the character it reads, and above the text.
        val yoru = units[0]
        assertTrue(yoru.rubyX < yoru.x + yoru.width / 2f && yoru.rubyBaseline < yoru.baseline)
        // Pieces follow one another with no gap, since the line is written without spaces.
        for ((before, after) in units.zipWithNext()) assertEquals(before.x + before.width, after.x, 0.01f)
        // All one line's window, unlike syllables.
        assertTrue(units.all { it.startMs == 1_000 && it.endMs == 4_000 })
    }

    @Test
    fun `katakana when asked, and room is kept above the text`() {
        val katakana = layout(furigana = false).units
        assertEquals("ヨル", katakana[0].ruby)
        val plain = layout(furigana = null)
        assertTrue(layout().contentHeight > plain.contentHeight)
        assertNull(plain.units.first().ruby)
    }

    @Test
    fun `unsynced lyrics get readings too, with no window to fill`() {
        val units = layout(kind = LyricsKind.STATIC).units
        assertEquals("よる", units[0].ruby)
        assertTrue(units.all { it.startMs == it.endMs })
    }

    @Test
    fun `romanization replaces the characters, so there is nothing to read over`() {
        val units = layout(line().copy(romanized = "yoru ni kakeru"), romanize = true).units
        assertEquals(listOf("yoru ni kakeru"), units.map { it.text })
        assertTrue(units.all { it.ruby == null })
    }

    @Test
    fun `a reading is never split from its characters by a row break`() {
        // 28 characters in a row 24 wide (the layout's floor is twice the font size): the first
        // piece fills the row exactly, so the break has to fall before 駆け, not inside it.
        val text = "夜に".repeat(12) + "駆ける夜"
        val wide = line(text = text, rubies = listOf(Ruby(24, 26, "カケ")))
        val units = layout(wide, widthPx = 24f / 0.95f).units
        val piece = units.single { it.ruby != null }
        assertEquals("駆け", piece.text)
        val rows = units.map { it.baseline }.distinct()
        assertEquals(2, rows.size)
        assertEquals(rows[1], piece.baseline, 0f)
        // And nothing was lost or doubled across the rows.
        assertEquals(text, units.joinToString("") { it.text })
    }

    @Test
    fun `spaces in the line stay as breaks between pieces`() {
        val spaced = line(text = "夜に 駆ける", rubies = listOf(Ruby(0, 1, "ヨル"), Ruby(3, 4, "カ")))
        val units = layout(spaced).units
        assertEquals(listOf("夜", "に", "駆", "ける"), units.map { it.text })
        // The space before 駆 is kept as a gap, not drawn as a piece.
        assertTrue(units[2].x > units[1].x + units[1].width)
        assertEquals(listOf("夜に 駆ける"), layout(spaced).flatRows.map { it.text })
    }

    @Test
    fun `a word-timed song's line that arrived as one block is read like a plain line`() {
        // Musixmatch times some lines as a single syllable; one reading over the whole line is no
        // gloss, so the readings go against the text and the line is placed piece by piece.
        val block = line().copy(syllables = listOf(com.melisma.app.lyrics.model.Syllable("夜に駆ける", 1_000, 4_000)))
        val units = layout(block, kind = LyricsKind.SYLLABLE).units
        assertEquals(listOf("夜", "に", "駆", "ける"), units.map { it.text })
        assertEquals(listOf("よる", null, "か", null), units.map { it.ruby })
        // Still one window: the sweep across the line is what a one-block line always had.
        assertTrue(units.all { it.startMs == 1_000 && it.endMs == 4_000 })
    }

    @Test
    fun `a run of kana longer than a row is broken, and the reading before it stays`() {
        // 夢 then fifteen kana, in a row 24 wide: the kana run alone fits, but 夢 and the run do not,
        // and a kana run of thirty would not fit any row. Neither may cost 夢 its reading.
        val short = line(text = "夢" + "な".repeat(15), rubies = listOf(Ruby(0, 1, "ユメ")))
        assertEquals("ゆめ", layout(short, widthPx = 24f / 0.95f).units.first().ruby)

        val long = line(text = "夢" + "な".repeat(30) + "光", rubies = listOf(Ruby(0, 1, "ユメ"), Ruby(31, 32, "ヒカリ")))
        val units = layout(long, widthPx = 24f / 0.95f).units
        assertEquals("ゆめ", units.first().ruby)
        assertEquals("ひかり", units.last().ruby)
        assertEquals("夢" + "な".repeat(30) + "光", units.joinToString("") { it.text })
        // The run fills the rest of 夢's row rather than starting one of its own: two rows, the
        // first full, with the kana on it continuing straight after 夢.
        val rows = units.groupBy { it.baseline }.values.toList()
        assertEquals(2, rows.size)
        assertEquals(24f, rows[0].sumOf { it.width.toDouble() }.toFloat(), 0.01f)
        assertEquals(listOf("夢", "な".repeat(23)), rows[0].map { it.text })
        assertEquals(listOf("な".repeat(7), "光"), rows[1].map { it.text })
    }

    @Test
    fun `a genuinely one-word line keeps its own syllable's timing`() {
        // The syllable is timed more closely than the line, so it is not a line-timed block in
        // disguise and the word's own window must survive — even with readings on hand.
        val word = com.melisma.app.lyrics.model.Syllable("夜に駆ける", 1_500, 3_200, kana = "ヨルニカケル")
        val timed = line().copy(syllables = listOf(word))
        val units = layout(timed, kind = LyricsKind.SYLLABLE).units
        assertTrue(units.all { it.startMs == 1_500 && it.endMs == 3_200 })
    }
}
