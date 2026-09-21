package com.melisma.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splitting a line by the script it is written in.
 *
 * Reported against "Chasing Lightning" by LE SSERAFIM, which is Korean and Japanese in the same song:
 * only one of the two was ever romanized. The cause was that romanization asked
 * [detectScript] — which answers with the *dominant* script — and one kana anywhere makes that answer
 * Japanese for the whole document. Every Korean line then went to a Japanese dictionary, which has
 * nothing to say about Hangul and hands the text back unchanged. Half a song romanized, no error
 * anywhere.
 *
 * These are the cases that decide whether the right engine is reached. They are deliberately pure —
 * no dictionary, no ICU — because the question is which engine, not what it produces.
 */
class ScriptRunsTest {

    private fun scriptsOf(text: String, hanScript: Script = Script.CHINESE) =
        scriptRuns(text, hanScript).map { it.script }

    @Test
    fun `Korean and Japanese in one line are two runs`() {
        val runs = scriptRuns("불꽃처럼 燃えろ", hanScript = Script.JAPANESE)
        assertEquals(listOf(Script.KOREAN, Script.JAPANESE), runs.map { it.script })
        // The space rides along with the run it follows rather than becoming a boundary of its own.
        assertEquals("불꽃처럼 ", runs[0].text)
        assertEquals("燃えろ", runs[1].text)
    }

    @Test
    fun `an English word in the middle survives as its own run`() {
        // The one that would be lost if a mixed line were simply handed to one engine: there is
        // nothing to convert in "BULLETPROOF", and it has to come back out the other side intact.
        val runs = scriptRuns("우린 BULLETPROOF 이야", hanScript = Script.JAPANESE)
        assertEquals(listOf(Script.KOREAN, Script.LATIN, Script.KOREAN), runs.map { it.script })
        assertEquals("BULLETPROOF ", runs[1].text)
    }

    @Test
    fun `one script is one run, whatever it is`() {
        assertEquals(listOf(Script.JAPANESE), scriptsOf("不完全な僕を", Script.JAPANESE))
        assertEquals(listOf(Script.KOREAN), scriptsOf("사랑해요"))
        assertEquals(listOf(Script.CYRILLIC), scriptsOf("Привет"))
        assertEquals(listOf(Script.GREEK), scriptsOf("Καλημέρα"))
        assertEquals(listOf(Script.LATIN), scriptsOf("Is this the real life"))
    }

    @Test
    fun `how a Han character is read is the caller's to decide`() {
        // 燃 is moeru in a Japanese song and rán in a Chinese one, and nothing about the character
        // says which. Getting this wrong is the failure that is hardest to notice: kanji read as
        // Mandarin still looks like a romanization.
        assertEquals(listOf(Script.JAPANESE), scriptsOf("燃", Script.JAPANESE))
        assertEquals(listOf(Script.CHINESE), scriptsOf("燃", Script.CHINESE))
    }

    @Test
    fun `kanji with kana beside it is all one Japanese run`() {
        // Not three runs of kanji and kana alternating: the reading of 食べて depends on the whole
        // word, so it has to reach the analyser whole.
        assertEquals(listOf(Script.JAPANESE), scriptsOf("食べて", Script.JAPANESE))
    }

    @Test
    fun `punctuation and digits never start a run of their own`() {
        assertEquals(listOf(Script.KOREAN), scriptsOf("사랑해요, 2번!"))
        assertEquals(listOf(Script.OTHER), scriptsOf("♪ … ♪"))
        assertEquals(emptyList<Script>(), scriptRuns("").map { it.script })
    }

    @Test
    fun `a run knows where it started`() {
        val runs = scriptRuns("불꽃 燃えろ", hanScript = Script.JAPANESE)
        assertEquals(0, runs[0].start)
        assertEquals(3, runs[1].start)
        // Which is the property the syllable mapping depends on: the offsets have to line up with the
        // line they were taken from, or a reading lands on the wrong word.
        assertEquals("불꽃 燃えろ".substring(runs[1].start), runs[1].text)
    }

    @Test
    fun `romanization is offered when any part of a song needs it`() {
        // The gate that decides whether the button appears at all. Asking the dominant script hid it
        // for exactly the songs that need it: mostly English, one Korean chorus.
        assertTrue(containsRomanizableScript("Every night, 사랑해"))
        assertTrue(containsRomanizableScript("燃えろ", Script.JAPANESE))
        assertFalse(containsRomanizableScript("Is this the real life"))
        assertFalse(containsRomanizableScript("♪ … ♪"))
    }

    @Test
    fun `detectScript still answers the question it is for`() {
        // Unchanged on purpose. Naming one language is right for a translator and for comparing two
        // artist names; it is only romanization that needed a finer answer.
        assertEquals(Script.JAPANESE, detectScript("불꽃처럼 燃えろ"))
    }
}
