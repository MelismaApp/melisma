package com.melisma.app.lyrics.romanize

import com.melisma.app.lyrics.parse.TtmlParser
import com.melisma.app.util.HanCanonical
import com.melisma.app.util.Script
import com.melisma.app.util.containsRomanizableScript
import com.melisma.app.util.detectScript
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characters that look like ideographs and are not.
 *
 * Reported as "some characters just show up as characters, no romanization" against 帶你飛 by 告五人.
 * The failures were 見, 貝 and 一 — ordinary characters that romanize fine when typed by hand — which
 * is what made it look like a romanizer fault. It was not: Apple Music sends those three as **Kangxi
 * Radicals**, a different block that exists for talking about radicals and renders identically.
 * Nothing that reads Chinese has a reading for them, so each one came out raw.
 *
 * The TTML below is copied verbatim from what Apple actually served for that track, so the lookalikes
 * in it are the real ones rather than characters typed to resemble the bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KangxiRadicalTest {

    /** U+2F92 KANGXI RADICAL SEE, not U+898B. */
    private val radicalSee = "⾒"

    /** U+2F99 KANGXI RADICAL SHELL, not U+8C9D. */
    private val radicalShell = "⾙"

    /** U+2F00 KANGXI RADICAL ONE, not U+4E00. */
    private val radicalOne = "⼀"

    /** Verbatim from Apple Music's response for 帶你飛, two lines of it. */
    private val appleTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            itunes:timing="Word" xml:lang="zh-Hant"><body>
        <div>
        <p begin="5.635" end="10.812" itunes:key="L1"><span begin="5.635" end="6.073">我</span><span begin="6.073" end="6.397">的</span><span begin="6.397" end="6.833">寶</span><span begin="6.833" end="7.363">$radicalShell</span></p>
        <p begin="18.330" end="25.417" itunes:key="L3"><span begin="18.330" end="19.102">期待</span><span begin="19.102" end="19.581">有</span><span begin="20.973" end="21.500">那麼</span><span begin="21.500" end="22.009">$radicalOne</span><span begin="22.009" end="22.881">天</span> <span begin="23.448" end="24.157">你能</span><span begin="24.157" end="24.631">看</span><span begin="24.631" end="25.417">$radicalSee</span></p>
        </div></body></tt>
    """.trimIndent()

    @Test
    fun `the lookalikes really are different characters`() {
        // Establishes the premise rather than assuming it: these are not the ideographs they look like.
        assertFalse(radicalSee == "見")
        assertFalse(radicalShell == "貝")
        assertFalse(radicalOne == "一")
        assertEquals("見", HanCanonical.of(radicalSee))
        assertEquals("貝", HanCanonical.of(radicalShell))
        assertEquals("一", HanCanonical.of(radicalOne))
    }

    @Test
    fun `canonicalising never changes the length`() {
        // The property the whole design leans on: a reading is handed back to its syllable by index,
        // so a normalisation that grew or shrank the text would misalign every reading after it.
        val line = "我的寶${radicalShell}${radicalOne}天$radicalSee"
        assertEquals(line.length, HanCanonical.of(line).length)
    }

    @Test
    fun `a line of them is still recognised as Chinese`() {
        // Before this they were not Han to the script detector either, so a line made mostly of them
        // could fail to register as romanizable at all and the button would not appear.
        val line = "${radicalOne}${radicalSee}$radicalShell"
        assertEquals(Script.CHINESE, detectScript(line))
        assertTrue(containsRomanizableScript(line, Script.CHINESE))
    }

    @Test
    fun `every syllable of the real Apple lines gets a reading`() = runBlocking {
        val parsed = TtmlParser.parse(appleTtml, "Apple Music", "applemusic")
        assertNotNull(parsed)

        val annotated = Romanizer().annotate(parsed!!, romanize = true, furigana = false)
        val unread = annotated.lines
            .flatMap { it.syllables }
            .filter { it.text.isNotBlank() && it.romanized.isNullOrBlank() }

        assertTrue(
            "these syllables came out with no reading: ${unread.map { it.text }}",
            unread.isEmpty(),
        )
    }

    @Test
    fun `and they get the right readings`() {
        val romanizer = Romanizer()
        assertEquals("jiàn", romanizer.romanizeText(radicalSee, Script.CHINESE))
        assertEquals("bèi", romanizer.romanizeText(radicalShell, Script.CHINESE))
        // Through a word, so the sandhi applies as it would for the ordinary character.
        assertEquals("yì tiān", romanizer.romanizeText("${radicalOne}天", Script.CHINESE))
    }

    @Test
    fun `the characters shown are still the ones the provider sent`() {
        // Deliberate: the two forms are visually identical, so rewriting the text would be an
        // invisible change to lyrics the app does not own. Only the lookup is normalised.
        val parsed = TtmlParser.parse(appleTtml, "Apple Music", "applemusic")!!
        val text = parsed.lines.joinToString("") { it.text }
        assertTrue("the original codepoints must survive", text.contains(radicalSee))
        assertTrue(text.contains(radicalShell))
    }
}
