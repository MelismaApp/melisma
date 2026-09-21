package com.melisma.app.lyrics.romanize

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
import com.melisma.app.util.containsNonLatinScript
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A song that changes language partway through.
 *
 * Reported against "Chasing Lightning" by LE SSERAFIM: Korean and Japanese in one track, and only one
 * of them was ever romanized. One kana anywhere made the whole document Japanese, so every Korean line
 * went to a Japanese dictionary — which has nothing to say about Hangul and returns it unchanged.
 *
 * This runs the real engines rather than the dispatch alone, because the dispatch was never the part
 * that looked broken: what the user saw was Hangul sitting on screen with romanization switched on,
 * and the only way to prove that is gone is to ask for a romanization and look at it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MixedScriptRomanizationTest {

    private fun syllables(vararg parts: String): List<Syllable> {
        var at = 0
        return parts.map { text ->
            val start = at
            at += 500
            Syllable(text = text, startMs = start, endMs = at)
        }
    }

    private fun line(vararg parts: String) = LyricLine(
        role = LineRole.LEAD,
        startMs = 0,
        endMs = 1_000 * parts.size,
        text = parts.joinToString(""),
        syllables = syllables(*parts),
    )

    private val romanizer = Romanizer()

    private fun romanize(document: LyricsDocument): LyricsDocument = runBlocking {
        romanizer.annotate(document, romanize = true, furigana = false)
    }

    private fun document(vararg lines: LyricLine) = LyricsDocument(
        kind = LyricsKind.SYLLABLE,
        lines = lines.toList(),
        providerName = "Test",
        providerId = "test",
    )

    @Test
    fun `both languages of a bilingual song are romanized`() {
        val before = document(
            line("불꽃", "처럼"),
            line("燃え", "ろ"),
        )

        val after = romanize(before)

        for ((index, result) in after.lines.withIndex()) {
            assertNotNull("line $index produced no romanization at all", result.romanized)
            assertFalse(
                "line $index came back still in its original script: ${result.romanized}",
                containsNonLatinScript(result.romanized!!),
            )
        }
    }

    @Test
    fun `a line that changes script mid-sentence is romanized throughout`() {
        val after = romanize(document(line("불꽃", "처럼", "燃え", "ろ")))
        val romanized = after.lines.single().romanized

        assertNotNull(romanized)
        assertFalse(
            "half of the line is still in its original script: $romanized",
            containsNonLatinScript(romanized!!),
        )
    }

    @Test
    fun `every syllable gets its own reading, so the karaoke fill has something to sweep`() {
        // The renderer fills syllable by syllable, so a line-level romanization is not enough: a
        // syllable left without one shows the original character mid-sweep.
        val after = romanize(document(line("불꽃", "처럼", "燃え", "ろ")))

        for (syllable in after.lines.single().syllables) {
            val reading = syllable.romanized
            assertNotNull("`${syllable.text}` has no reading of its own", reading)
            assertFalse(
                "`${syllable.text}` came back as `$reading`",
                containsNonLatinScript(reading!!),
            )
        }
    }

    @Test
    fun `an English word inside a Korean line is left alone rather than lost`() {
        val after = romanize(document(line("우린", " ", "BULLETPROOF")))
        val romanized = after.lines.single().romanized

        assertNotNull(romanized)
        assertTrue(
            "the English word should survive untouched: $romanized",
            romanized!!.contains("BULLETPROOF", ignoreCase = true),
        )
    }

    @Test
    fun `a song in one language is unaffected`() {
        // The regression guard: none of this may change what a single-language song produces.
        val after = romanize(document(line("사랑", "해요")))
        val romanized = after.lines.single().romanized

        assertNotNull(romanized)
        assertFalse(containsNonLatinScript(romanized!!))
    }

    @Test
    fun `a romanization the provider shipped is never overwritten`() {
        val supplied = document(
            LyricLine(
                role = LineRole.LEAD,
                startMs = 0,
                endMs = 1_000,
                text = "불꽃처럼",
                syllables = emptyList(),
                romanized = "bulkkotcheoreom (theirs)",
            ),
        )

        val after = romanize(supplied)
        assertTrue(after.lines.single().romanized!!.contains("(theirs)"))
    }
}
