package com.melisma.app.ui.lyrics

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keeping the characters under a reading that replaced them.
 *
 * Asked for because a romanization is lossy in a way that cannot be repaired: ICU gives every Chinese
 * character one fixed reading regardless of the word it sits in, so 音乐 comes out `yin le` where it
 * is said *yinyue*, and 的 is always `de` where it can be *dí*, *dì* or *dé*. The characters are the
 * only thing on screen that says which word was meant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeepCharactersTest {

    private val metrics =
        LyricsMetrics(fontSizePx = 10f, simpleMode = false, showSecondaryLine = true)

    /** Syllable-timed, so the reading takes the place of the characters in the swept text. */
    private fun syllableTimed() = LyricsDocument(
        kind = LyricsKind.SYLLABLE,
        lines = listOf(
            LyricLine(
                role = LineRole.LEAD,
                startMs = 0,
                endMs = 2_000,
                text = "音乐",
                syllables = listOf(
                    Syllable("音", 0, 1_000, romanized = "yīn"),
                    Syllable("乐", 1_000, 2_000, romanized = "lè"),
                ),
                translated = "music",
            ),
        ),
        providerName = "test",
        providerId = "test",
    )

    /** Line-level romanization only, so the characters are already the main text. */
    private fun lineLevelOnly() = LyricsDocument(
        kind = LyricsKind.SYLLABLE,
        lines = listOf(
            LyricLine(
                role = LineRole.LEAD,
                startMs = 0,
                endMs = 2_000,
                text = "音乐",
                syllables = listOf(Syllable("音", 0, 1_000), Syllable("乐", 1_000, 2_000)),
                romanized = "yīn lè",
            ),
        ),
        providerName = "test",
        providerId = "test",
    )

    private fun lineFor(
        document: LyricsDocument,
        showOriginal: Boolean,
        showTranslation: Boolean = false,
    ) = LyricsLayoutBuilder.build(
        document = document,
        metrics = metrics,
        widthPx = 1_000f,
        useRomanization = true,
        showTranslation = showTranslation,
        showOriginal = showOriginal,
        showCredits = false,
    ).lines[0]

    private fun rowsFor(
        document: LyricsDocument,
        showOriginal: Boolean,
        showTranslation: Boolean = false,
    ): List<String> = lineFor(document, showOriginal, showTranslation).secondaryRows.map { it.text }

    /** The characters carried per syllable, in order. */
    private fun charactersFor(
        document: LyricsDocument,
        showOriginal: Boolean,
    ): List<String> = lineFor(document, showOriginal).units.mapNotNull { it.under }

    @Test
    fun `the room for them is reserved so lines do not collide`() {
        val without = lineFor(syllableTimed(), showOriginal = false)
        val with = lineFor(syllableTimed(), showOriginal = true)
        assertTrue(
            "a line showing characters must be taller: ${with.height} vs ${without.height}",
            with.height > without.height,
        )
    }

    @Test
    fun `on, each syllable carries its own character`() {
        // Per syllable, not a row of text: that is what lets you see which character the reading in
        // front of you came from.
        assertEquals(listOf("音", "乐"), charactersFor(syllableTimed(), showOriginal = true))
        // And not as a wrapped secondary row, which could not be aligned or swept.
        assertEquals(emptyList<String>(), rowsFor(syllableTimed(), showOriginal = true))
    }

    @Test
    fun `off, no syllable carries one`() {
        assertEquals(emptyList<String>(), charactersFor(syllableTimed(), showOriginal = false))
    }

    @Test
    fun `each character is centred under the syllable it belongs to, and shares its timings`() {
        // The two properties the animation needs: alignment, so the character sits under its own
        // reading, and the syllable's own window, so the fill reaches both at the same moment.
        val line = lineFor(syllableTimed(), showOriginal = true)
        val timed = line.units.filter { it.under != null }
        assertEquals(2, timed.size)

        for (unit in timed) {
            val centreOfWord = unit.x + unit.width / 2f
            val centreOfCharacter = unit.underX + metrics.secondaryPaint.measureText(unit.under!!) / 2f
            assertTrue(
                "character centred at $centreOfCharacter, word at $centreOfWord",
                kotlin.math.abs(centreOfCharacter - centreOfWord) < 0.5f,
            )
            assertTrue("characters sit below the reading", unit.underBaseline > unit.baseline)
        }

        assertEquals(0, timed[0].startMs)
        assertEquals(1_000, timed[0].endMs)
        assertEquals(1_000, timed[1].startMs)
        assertEquals(2_000, timed[1].endMs)
    }

    @Test
    fun `the reading stays the text being sung`() {
        // The point of putting the characters in the small row rather than the big one: the karaoke
        // fill still follows the romanization, which is what someone reading it is singing from.
        val line = LyricsLayoutBuilder.build(
            document = syllableTimed(),
            metrics = metrics,
            widthPx = 1_000f,
            useRomanization = true,
            showTranslation = false,
            showOriginal = true,
            showCredits = false,
        ).lines[0]

        assertTrue(line.units.joinToString("") { it.text }.contains("yīn"))
    }

    @Test
    fun `a translation still gets its own row underneath`() {
        assertEquals(
            listOf("music"),
            rowsFor(syllableTimed(), showOriginal = true, showTranslation = true),
        )
    }

    @Test
    fun `a line whose reading is already underneath is not doubled up`() {
        // Here the characters are the main text and the reading is the secondary row, so adding the
        // characters again would print the same line twice.
        assertEquals(listOf("yīn lè"), rowsFor(lineLevelOnly(), showOriginal = true))
    }

    @Test
    fun `a line with nothing to show underneath is no taller`() {
        // Reserving the band for every syllable-timed line put an empty row under the English lines
        // of a mixed-language song, which reads as the spacing going wrong for no reason.
        val latin = LyricsDocument(
            kind = LyricsKind.SYLLABLE,
            lines = listOf(
                LyricLine(
                    role = LineRole.LEAD,
                    startMs = 0,
                    endMs = 2_000,
                    text = "hello there",
                    syllables = listOf(Syllable("hello", 0, 1_000), Syllable("there", 1_000, 2_000)),
                ),
            ),
            providerName = "test",
            providerId = "test",
        )
        assertEquals(
            lineFor(latin, showOriginal = false).height,
            lineFor(latin, showOriginal = true).height,
            0.01f,
        )
    }

    @Test
    fun `a wrapped line keeps every character underneath`() {
        // A spaceless line is one long word, so it goes through the chunking path — which used to
        // rebuild the first piece of each row without its character, losing one per wrap.
        val text = "音乐音乐音乐音乐音乐音乐音乐音乐"
        val document = LyricsDocument(
            kind = LyricsKind.SYLLABLE,
            lines = listOf(
                LyricLine(
                    role = LineRole.LEAD,
                    startMs = 0,
                    endMs = 8_000,
                    text = text,
                    syllables = text.mapIndexed { index, char ->
                        Syllable(
                            char.toString(),
                            index * 500,
                            (index + 1) * 500,
                            partOfWord = index > 0,
                            romanized = if (char == '音') "yīn" else "yuè",
                        )
                    },
                ),
            ),
            providerName = "test",
            providerId = "test",
        )

        // Narrow enough that the single word must be split across rows.
        val line = LyricsLayoutBuilder.build(
            document = document,
            metrics = metrics,
            widthPx = 60f,
            useRomanization = true,
            showTranslation = false,
            showOriginal = true,
            showCredits = false,
        ).lines[0]

        assertEquals(text.length, line.units.count { it.under != null })
        assertEquals(text, line.units.mapNotNull { it.under }.joinToString(""))
    }

    @Test
    fun `an RTL line with no reading is not printed twice`() {
        // Arabic and Hebrew have no romanizer, so the word is drawn as itself — and it used to be
        // drawn as itself underneath as well.
        val arabic = LyricsDocument(
            kind = LyricsKind.SYLLABLE,
            lines = listOf(
                LyricLine(
                    role = LineRole.LEAD,
                    startMs = 0,
                    endMs = 2_000,
                    text = "مرحبا",
                    syllables = listOf(Syllable("مرحبا", 0, 2_000)),
                    rtl = true,
                ),
            ),
            providerName = "test",
            providerId = "test",
        )
        assertEquals(emptyList<String>(), charactersFor(arabic, showOriginal = true))
    }

    @Test
    fun `nothing is added to a line that was never romanized`() {
        val latin = LyricsDocument(
            kind = LyricsKind.SYLLABLE,
            lines = listOf(
                LyricLine(
                    role = LineRole.LEAD,
                    startMs = 0,
                    endMs = 2_000,
                    text = "hello there",
                    syllables = listOf(
                        Syllable("hello", 0, 1_000),
                        Syllable("there", 1_000, 2_000),
                    ),
                ),
            ),
            providerName = "test",
            providerId = "test",
        )
        assertEquals(emptyList<String>(), charactersFor(latin, showOriginal = true))
        assertEquals(emptyList<String>(), rowsFor(latin, showOriginal = true))
    }
}
