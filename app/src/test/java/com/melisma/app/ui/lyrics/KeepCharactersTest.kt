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

    private fun rowsFor(
        document: LyricsDocument,
        showOriginal: Boolean,
        showTranslation: Boolean = false,
    ): List<String> = LyricsLayoutBuilder.build(
        document = document,
        metrics = metrics,
        widthPx = 1_000f,
        useRomanization = true,
        showTranslation = showTranslation,
        showOriginal = showOriginal,
        showCredits = false,
    ).lines[0].secondaryRows.map { it.text }

    @Test
    fun `off, the characters the reading replaced are simply gone`() {
        assertEquals(emptyList<String>(), rowsFor(syllableTimed(), showOriginal = false))
    }

    @Test
    fun `on, they come back underneath`() {
        assertEquals(listOf("音乐"), rowsFor(syllableTimed(), showOriginal = true))
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
    fun `characters sit between the reading and the meaning`() {
        assertEquals(
            listOf("音乐", "music"),
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
        assertEquals(emptyList<String>(), rowsFor(latin, showOriginal = true))
    }
}
