package com.melisma.app.lyrics.romanize

/**
 * Traditional characters to Simplified, one UTF-16 unit for one, so a reading found for the folded
 * text lands on the same index in the original.
 *
 * For looking words up, never for display: the word tables are keyed one way, and lyrics arrive in
 * either. OpenCC's `TSCharacters.txt` (Apache-2.0) reduced to single characters inside the BMP, built
 * by `tools/hokkien/build_tables.py`.
 */
internal object HanFold {

    private val table: Map<Char, Char> by lazy {
        val out = HashMap<Char, Char>(1 shl 12)
        val stream = HanFold::class.java.getResourceAsStream("/hokkien/fold.txt")
        runCatching {
            stream?.bufferedReader()?.use { reader ->
                reader.forEachLine { line -> if (line.length == 3 && line[1] == '\t') out[line[0]] = line[2] }
            }
        }
        out
    }

    fun fold(text: String): String {
        val map = table
        var changed = false
        val out = CharArray(text.length) { index ->
            val char = text[index]
            (map[char] ?: char).also { if (it != char) changed = true }
        }
        return if (changed) String(out) else text
    }

    /**
     * CJK ideographs, Extension A, and the compatibility block. Written as escapes: U+F900 looks
     * exactly like U+8C48, and a range typed from the latter took in every Hangul syllable.
     */
    fun isHan(char: Char): Boolean =
        char in '\u4e00'..'\u9fff' || char in '\u3400'..'\u4dbf' || char in '\uf900'..'\ufaff'
}
