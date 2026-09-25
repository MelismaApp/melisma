package com.melisma.app.lyrics.romanize

import java.io.BufferedReader

/**
 * Pinyin for whole words, because a reading per character is not enough to be right.
 *
 * ICU's `Han-Latin` gives every character one fixed reading with no regard for the word it sits in.
 * Measured over 264,677 words containing a character that has more than one reading, it is wrong for
 * **31,710** of them — 音乐 as `yin le` where it is *yinyue*, 了解 as `le jie` where it is *liaojie*,
 * 地方 as `de fang` where it is *difang*. Those 31,710 are what this table holds, and nothing else:
 * where ICU already agrees with the dictionary there is nothing to store.
 *
 * It also picks up the tone sandhi of 一 and 不, which are rules rather than exceptions but come out
 * of the same lookup: 不要 is *bú yào* and 一起 is *yì qǐ*, neither of which a per-character table can
 * produce.
 *
 * The data is the non-CC-CEDICT half of
 * [phrase-pinyin-data](https://github.com/mozillazg/phrase-pinyin-data) (MIT) — its own `pinyin.txt`,
 * `overwrite.txt`, `di.txt` and the two 汉典 files. Its combined `large_pinyin.txt` folds in
 * CC-CEDICT, which is CC-BY-SA, and an upstream relicence does not travel; those entries are left
 * out rather than relied on. See `docs/ROMANIZATION.md`.
 *
 * The table is in Simplified characters, so a line is looked up folded: 音樂 is 音乐 to it.
 *
 * Held as a classpath resource rather than an asset so it needs no `Context`, which is how Kuromoji's
 * dictionary already arrives. Loaded on the first Chinese line of the session and never on the main
 * thread; 295 KB in the APK, a few megabytes of heap, against the tens of megabytes Kuromoji costs
 * for the same job in Japanese.
 */
object PinyinWords {

    private const val RESOURCE = "/pinyin/words.txt"

    /** The longest word in the table; the segmenter never needs to look further ahead than this. */
    private var longest = 2

    private val table: Map<String, String> by lazy { load() }

    /** The particle reading of each character a lyric almost always means as one. */
    private val particles = mapOf('的' to "de", '了' to "le", '着' to "zhe")

    /**
     * Two-character words that read one of [particles] otherwise at an edge, and are common enough
     * to be meant when they appear. The rest are dropped: longest match takes the first word it
     * finds, so 的真 *dí zhēn*, "genuine", read 愛你的真心 as *dí zhēn xīn*, 着手 made 牽著手
     * *zhuó shǒu*, and 明了 made 說明了 *míng liǎo*.
     */
    private val kept = setOf(
        "的确", "的士", "的哥", "目的",
        "了解", "了断", "不了", "未了",
        "执着", "着急", "着迷", "着凉", "着火", "着魔", "着慌", "睡着", "不着",
    )

    /**
     * Words ICU already reads right, listed so the ones kept above cannot take a character from them:
     * 为了解决 is not *wèi liǎo jiě*, and 盲目的爱 is not *máng mù dì*.
     */
    private val added = mapOf(
        "为了" to "wèi le", "除了" to "chú le",
        "盲目" to "máng mù", "醒目" to "xǐng mù", "注目" to "zhù mù", "夺目" to "duó mù",
        "瞩目" to "zhǔ mù", "耀目" to "yào mù", "炫目" to "xuàn mù", "悦目" to "yuè mù",
        "节目" to "jié mù", "项目" to "xiàng mù", "题目" to "tí mù", "面目" to "miàn mù",
    )

    private fun stealsParticle(word: String, reading: String): Boolean {
        if (word.length != 2 || word in kept) return false
        val syllables = reading.split(' ')
        if (syllables.size != 2) return false
        return (0..1).any { i -> particles[word[i]]?.let { it != syllables[i] } == true }
    }

    private fun load(): Map<String, String> {
        val stream = PinyinWords::class.java.getResourceAsStream(RESOURCE) ?: return emptyMap()
        val out = HashMap<String, String>(1 shl 16)
        runCatching {
            stream.bufferedReader().use { reader: BufferedReader ->
                reader.forEachLine { raw ->
                    val tab = raw.indexOf('\t')
                    if (tab > 0 && tab < raw.length - 1) {
                        val word = raw.substring(0, tab)
                        val reading = raw.substring(tab + 1)
                        if (stealsParticle(word, reading)) return@forEachLine
                        out[word] = reading
                        if (word.length > longest) longest = word.length
                    }
                }
            }
        }
        if (out.isNotEmpty()) out.putAll(added)
        return out
    }

    /** Whether the table loaded at all. A missing resource degrades to ICU rather than failing. */
    val isAvailable: Boolean get() = table.isNotEmpty()

    /**
     * A reading per character of [text], or null where no word covered it.
     *
     * Longest match forward, which is the standard way to segment a script with no spaces and is
     * enough here: the table only holds words ICU gets wrong, so an unmatched stretch is one ICU was
     * already right about. The caller fills those from ICU, so a line is a mix of the two — which is
     * the point, since replacing ICU wholesale would mean shipping a reading for every word in the
     * language rather than the ones that were broken.
     */
    fun readings(text: String): Array<String?>? {
        val words = table
        if (words.isEmpty() || text.isEmpty()) return null
        val key = HanFold.fold(text)

        val out = arrayOfNulls<String>(key.length)
        var found = false
        var index = 0
        while (index < key.length) {
            var matched = 0
            var size = minOf(longest, key.length - index)
            while (size >= 2) {
                val reading = words[key.substring(index, index + size)]
                if (reading != null) {
                    val syllables = reading.split(' ')
                    // The generator keeps only entries with one syllable per character, so this
                    // holds; checking anyway, because a malformed line must not shift every reading
                    // after it onto the wrong character.
                    if (syllables.size == size) {
                        for (offset in 0 until size) out[index + offset] = syllables[offset]
                        matched = size
                        found = true
                    }
                    break
                }
                size--
            }
            index += if (matched > 0) matched else 1
        }
        return if (found) out else null
    }
}
