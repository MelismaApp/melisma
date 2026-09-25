package com.melisma.app.lyrics.romanize

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
import com.melisma.app.settings.ChineseReading
import com.melisma.app.settings.HokkienSpelling
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A song read as Taiwanese Hokkien, end to end through the romanizer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HokkienRomanizationTest {

    private val romanizer = Romanizer()

    private fun timed(vararg parts: String) = LyricLine(
        role = LineRole.LEAD,
        startMs = 0,
        endMs = 500 * parts.size,
        text = parts.joinToString(""),
        syllables = parts.mapIndexed { i, text -> Syllable(text = text, startMs = i * 500, endMs = (i + 1) * 500) },
    )

    private fun plain(text: String, romanized: String? = null) =
        LyricLine(role = LineRole.LEAD, startMs = 0, endMs = 1_000, text = text, romanized = romanized)

    private fun document(vararg lines: LyricLine) =
        LyricsDocument(kind = LyricsKind.SYLLABLE, lines = lines.toList(), providerName = "Test", providerId = "test")

    private fun annotate(
        document: LyricsDocument,
        chinese: ChineseReading,
        spelling: HokkienSpelling = HokkienSpelling.TAILO,
        strip: Boolean = false,
    ) = runBlocking {
        romanizer.annotate(document, romanize = true, furigana = false, stripDiacritics = strip, chinese = chinese, hokkienSpelling = spelling)
    }

    @Test
    fun `a word spanning timed syllables is hyphenated across them`() {
        val line = annotate(document(timed("原", "來", "心", "肝")), ChineseReading.HOKKIEN).lines.single()
        assertEquals(listOf("guân", "-lâi", "sim", "-kuann"), line.syllables.map { it.romanized })
        assertEquals(listOf(true, false, true, false), line.syllables.map { it.romanizedStartsWord })
        assertEquals("guân-lâi sim-kuann", line.romanized)
    }

    @Test
    fun `what a provider shipped is replaced, so the song is spelled one way`() {
        // NetEase's romanization for Hokkien songs is its own, pinyin-like and without tones.
        val line = annotate(document(plain("原來", romanized = "guan lai")), ChineseReading.HOKKIEN).lines.single()
        assertEquals("guân-lâi", line.romanized)
    }

    @Test
    fun `Mandarin keeps its pinyin and what the provider sent`() {
        val shipped = annotate(document(plain("原來", romanized = "yuan lai")), ChineseReading.MANDARIN).lines.single()
        assertEquals("yuan lai", shipped.romanized)
        val generated = annotate(document(plain("原來")), ChineseReading.MANDARIN).lines.single()
        assertFalse(generated.romanized!!.contains("guân"))
    }

    @Test
    fun `Auto asks the detector`() {
        val hokkien = document(plain("我毋知影你佇佗位"), plain("阮兜的囡仔攏足乖"), plain("伊講明仔載欲來揣我"))
        assertTrue(annotate(hokkien, ChineseReading.AUTO).lines.first().romanized!!.startsWith("guá m̄"))
        val mandarin = document(plain("我不知道你在哪裡"), plain("我們家的孩子都很乖"), plain("他說明天要來找我"))
        assertTrue(annotate(mandarin, ChineseReading.AUTO).lines.first().romanized!!.startsWith("wǒ"))
    }

    @Test
    fun `POJ with tone marks dropped keeps its o dot`() {
        val line = annotate(document(plain("查某")), ChineseReading.HOKKIEN, HokkienSpelling.POJ, strip = true).lines.single()
        assertEquals("cha-bo͘", line.romanized)
    }

    @Test
    fun `Auto leaves a Korean song to Korean romanization`() {
        val song = document(timed("어지러워 ", "너의 ", "그 ", "미소"), timed("눈이 ", "멀어 ", "Stuck ", "in ", "your ", "halo"))
        val lines = annotate(song, ChineseReading.AUTO).lines
        assertEquals("eojileowo neoui geu miso", lines[0].romanized)
        assertTrue(lines[1].romanized!!.startsWith("nun-i meol-eo"))
    }

    @Test
    fun `a Korean phrase in a Hokkien song is still romanized`() {
        val line = annotate(document(timed("原", "來 ", "사랑", "해")), ChineseReading.HOKKIEN).lines.single()
        assertEquals("guân", line.syllables[0].romanized)
        assertEquals("salang", line.syllables[2].romanized)
    }
}
