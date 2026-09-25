package com.melisma.app.lyrics.romanize

/**
 * Whether a song's Chinese characters are Taiwanese Hokkien rather than Mandarin.
 *
 * The script cannot say: both are written in the same characters, and the app used to read every one
 * as Mandarin. What differs is which characters and pairs turn up, so each is weighed by how much
 * more often it appears in Hokkien than in Mandarin, from dictionary text of both (see
 * `tools/hokkien/build_tables.py`), and the song's average decides.
 *
 * Decided for the whole song, as Japanese is: a Hokkien lyric is full of lines that could be either,
 * and only the rest of the song can say which. Built to rather miss a Hokkien song than misread a
 * Mandarin one — a miss leaves the pinyin that was always there, and the song can be told otherwise
 * in This track.
 *
 * Only a song mostly in Chinese characters is considered; see [isHokkien].
 *
 * What it cannot see is a song written almost entirely in characters both languages share. A handful
 * of words only Hokkien uses tip those, when the score is close enough to be unsure.
 */
object HokkienDetector {

    private class Model(val chars: Map<Char, Float>, val pairs: Map<String, Float>, val charFloor: Float, val pairFloor: Float)

    private val model: Model by lazy { load() }

    private fun load(): Model {
        val chars = HashMap<Char, Float>(1 shl 13)
        val pairs = HashMap<String, Float>(1 shl 14)
        var charFloor = 0f
        var pairFloor = 0f
        var section = ""
        val stream = HokkienDetector::class.java.getResourceAsStream("/hokkien/detect.txt")
        runCatching {
            stream?.bufferedReader()?.use { reader ->
                reader.forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val key = line.substring(0, tab)
                    val weight = line.substring(tab + 1).toFloatOrNull() ?: return@forEachLine
                    when {
                        key == "#u" -> { section = "u"; charFloor = weight }
                        key == "#b" -> { section = "b"; pairFloor = weight }
                        section == "u" && key.length == 1 -> chars[key[0]] = weight
                        section == "b" && key.length == 2 -> pairs[key] = weight
                    }
                }
            }
        }
        return Model(chars, pairs, charFloor, pairFloor)
    }

    /** Below this many characters a song says too little to decide on. */
    private const val MIN_CHARACTERS = 20

    /** An average above this is Hokkien. The closest Mandarin song measured scored -0.07. */
    private const val THRESHOLD = 0.0f

    /** Close enough to be unsure: here two Hokkien-only words decide it. */
    private const val UNSURE = -0.4f
    private const val MARKERS_NEEDED = 2

    /**
     * Words Mandarin does not use, folded to Simplified like the text. 佇 only where it is not 佇立,
     * which Mandarin does say.
     */
    private val markers = listOf(
        "明仔载", "知影", "按怎", "啥物", "囡仔", "查某", "恁", "袂", "毋", "喙", "厝", "媠", "佇", "拢是",
    )
    private val notMarkers = listOf("佇立", "佇候", "佇足")

    /**
     * Characters written Cantonese uses and Hokkien does not; enough of them and it is not Hokkien.
     * Folded to Simplified, like the text they are counted in: 諗 is 谂 by then.
     */
    private const val CANTONESE = "嘅唔咗喺冇佢哋乜嘢啲睇嚟谂嗰咁咩梗攞嘥"

    /** "作词 : …", "编曲：…" — credits, in Mandarin whatever the song is in. */
    private val credit = Regex("^\\s*[^:：\\s]{1,16}\\s*[:：]")

    fun isHokkien(lines: List<String>): Boolean {
        val m = model
        if (m.chars.isEmpty()) return false
        val text = HokkienWords.fold(lines.filterNot { credit.containsMatchIn(it) }.joinToString("\n"))
        val han = text.filter(HanFold::isHan)
        if (han.length < MIN_CHARACTERS) return false
        // Only a song whose own script is Chinese: a Korean one with a line or two of Chinese is not
        // Hokkien, whatever those lines score. Latin does not count against it — 愛到明仔載 is half
        // English, and English is left as it is either way.
        val otherScripts = text.count {
            it.isLetter() && !HanFold.isHan(it) && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN
        }
        if (otherScripts > han.length) return false
        if (han.count { it in CANTONESE } * 50 >= han.length) return false

        var sum = 0.0
        for (char in han) sum += m.chars[char] ?: m.charFloor
        for (i in 0 until han.length - 1) sum += 0.5 * (m.pairs[han.substring(i, i + 2)] ?: m.pairFloor)
        val score = sum / han.length
        if (score >= THRESHOLD) return true
        if (score < UNSURE) return false

        var remaining = text
        for (word in notMarkers) remaining = remaining.replace(HokkienWords.fold(word), "")
        val found = markers.sumOf { word -> occurrences(remaining, HokkienWords.fold(word)) }
        return found >= MARKERS_NEEDED
    }

    private fun occurrences(text: String, word: String): Int {
        var count = 0
        var from = text.indexOf(word)
        while (from >= 0) {
            count++
            from = text.indexOf(word, from + word.length)
        }
        return count
    }

}
