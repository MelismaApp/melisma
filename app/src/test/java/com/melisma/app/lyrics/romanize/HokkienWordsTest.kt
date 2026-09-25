package com.melisma.app.lyrics.romanize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** Taiwanese Hokkien in Tâi-lô and POJ, from dictionary words rather than lyrics. */
class HokkienWordsTest {

    @Test
    fun `syllables of a word are hyphenated and words are spaced`() {
        assertEquals("kóng tâi-gí", HokkienWords.romanize("講台語"))
        assertEquals("tsa-bóo-gín-á", HokkienWords.romanize("查某囡仔"))
        assertEquals("bîn-á-tsài", HokkienWords.romanize("明仔載"))
        // "--" before a neutral tone, as the dictionaries write it.
        assertEquals("tsia̍h-pá--buē", HokkienWords.romanize("食飽未"))
    }

    @Test
    fun `each character's reading says whether it starts a word`() {
        val readings = HokkienWords.readings("原來")!!
        assertEquals("guân", readings[0]!!.syllable)
        assertEquals("", readings[0]!!.join)
        assertEquals("lâi", readings[1]!!.syllable)
        assertEquals("-", readings[1]!!.join)
    }

    @Test
    fun `a character lyrics write the Mandarin way is sung as the Hokkien word`() {
        // 會 is ē, "will", on its own; the dictionaries' default is huē, as in 會議.
        assertEquals("sim ē thiànn", HokkienWords.romanize("心會痛"))
        // Inside a listed word it keeps the word's reading.
        assertEquals("put-kò", HokkienWords.romanize("不過"))
    }

    @Test
    fun `simplified characters read the same as traditional`() {
        assertEquals("bô-îng", HokkienWords.romanize("無閒"))
        assertEquals("bô-îng", HokkienWords.romanize("无闲"))
        // OpenCC does not fold 著 to 着, which Simplified lyrics use for tio̍h.
        assertEquals(HokkienWords.romanize("著"), HokkienWords.romanize("着"))
        assertEquals("tio̍h", HokkienWords.romanize("着"))
    }

    @Test
    fun `what has no reading is left as it was`() {
        assertEquals("Hello lí hó", HokkienWords.romanize("Hello 你好"))
        assertEquals("（guân-lâi）", HokkienWords.romanize("（原來）"))
        assertEquals("tsai-iánn！", HokkienWords.romanize("知影！"))
        assertNull(HokkienWords.romanize("Hello"))
    }

    @Test
    fun `a character outside the basic plane is read whole`() {
        assertEquals("gâu", HokkienWords.romanize("𠢕"))
    }

    @Test
    fun `POJ is spelled as the dictionary spells it`() {
        assertEquals("sim-koaⁿ", HokkienWords.romanize("心肝", poj = true))
        assertEquals("cha-bó͘-gín-á", HokkienWords.romanize("查某囡仔", poj = true))
        // POJ marks the u of ui, where Tâi-lô marks the i.
        assertEquals("lí tī tó-ūi", HokkienWords.romanize("你佇佗位", poj = true))
    }

    @Test
    fun `a syllable the dictionary never spells in POJ is converted by rule`() {
        assertEquals("kóa", TaiLo.toPoj("kuá"))
        assertEquals("chhiūⁿ", TaiLo.toPoj("tshiūnn"))
        assertEquals("seng", TaiLo.toPoj("sing"))
        assertEquals("thó͘", TaiLo.toPoj("thóo"))
        assertEquals("ôe", TaiLo.toPoj("uê"))
    }

    @Test
    fun `dropping tone marks keeps the dot of POJ's o`() {
        assertEquals("ko͘-toaⁿ kiann", HokkienWords.stripTones("ko͘-toaⁿ kiânn"))
        assertEquals("tsa-boo", HokkienWords.stripTones("tsa-bóo"))
    }

    @Test
    fun `a downloaded dictionary is read first, where it disagrees`() {
        val before = HokkienWords.revision.value
        try {
            val changed = HokkienWords.useDictionary(
                mapOf(
                    // Disagrees: the dictionary wins.
                    "聽著" to listOf("thiann--tio̍h"),
                    // Lists the bundled reading among its own: the bundled one stays.
                    "心肝" to listOf("sin-kuann", "sim-kuann"),
                    // A single character is never taken from it.
                    "人" to listOf("jîn"),
                    // A word the bundled tables do not have.
                    "八字骹" to listOf("pat-jī-kha"),
                ),
            )
            assertEquals(2, changed)
            assertTrue(HokkienWords.revision.value != before)
            assertEquals("thiann--tio̍h", HokkienWords.romanize("聽著"))
            assertEquals("sim-kuann", HokkienWords.romanize("心肝"))
            assertEquals("lâng", HokkienWords.romanize("人"))
            assertEquals("pat-jī-kha", HokkienWords.romanize("八字骹"))
            // Simplified lyrics are read through it too.
            assertEquals("thiann--tio̍h", HokkienWords.romanize("听着"))
        } finally {
            HokkienWords.useDictionary(null)
        }
        assertEquals("thiann-tio̍h", HokkienWords.romanize("聽著"))
    }
}
