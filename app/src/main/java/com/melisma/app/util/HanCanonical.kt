package com.melisma.app.util

import android.icu.text.Normalizer2

/**
 * The ordinary ideograph behind a lookalike one.
 *
 * Reported as "some characters just show up as characters, no romanization", against 帶你飛 by 告五人 from
 * Apple Music. The characters that failed were not unusual words — 見, 貝, 一 — and romanizing them by
 * hand worked. What Apple actually sent was not those characters:
 *
 * ```
 * ⾒ U+2F92 KANGXI RADICAL SEE    instead of 見 U+898B
 * ⾙ U+2F99 KANGXI RADICAL SHELL  instead of 貝 U+8C9D
 * ⼀ U+2F00 KANGXI RADICAL ONE    instead of 一 U+4E00
 * ```
 *
 * Kangxi Radicals are a separate block that exists so dictionaries can talk *about* radicals. They
 * render identically to the ideographs they depict and are different codepoints, so nothing that reads
 * Chinese has a reading for them: ICU returns them untouched, no word in any dictionary contains them,
 * and the character is shown raw where its reading should be. Nine of them in that one song, which is
 * why only some characters failed.
 *
 * This maps them back, one character in and one character out. That matters: a reading is handed back
 * to the syllable it came from by index, so a normalisation that changed a string's length — which
 * full NFKC does, turning ﷺ into thirty-odd letters — would put every reading after it on the wrong
 * character. Only single-character results are accepted, so the length cannot change.
 *
 * **For lookups only.** What the provider sent is what gets drawn: the two forms are visually
 * identical, so rewriting the display would be a change nobody could see, made to text the app does
 * not own.
 */
object HanCanonical {

    /** Blocks whose characters are lookalikes of ordinary ideographs. */
    private fun Char.isLookalike(): Boolean =
        this in '⺀'..'⻳' || // CJK Radicals Supplement
            this in '⼀'..'⿕' || // Kangxi Radicals
            this in '豈'..'﫿' // CJK Compatibility Ideographs

    private val nfkc: Normalizer2? by lazy {
        runCatching { Normalizer2.getNFKCInstance() }.getOrNull()
    }

    private val cache = HashMap<Char, Char>(256)

    private fun canonical(char: Char): Char = cache.getOrPut(char) {
        val normalizer = nfkc ?: return@getOrPut char
        val normalized = runCatching { normalizer.normalize(char.toString()) }.getOrNull()
        if (normalized != null && normalized.length == 1) normalized[0] else char
    }

    /**
     * [text] with every lookalike replaced by the ideograph it depicts, and the same length.
     *
     * Returns the same instance when there is nothing to do, which is almost always.
     */
    fun of(text: String): String {
        if (text.none { it.isLookalike() }) return text
        val out = StringBuilder(text.length)
        for (char in text) out.append(if (char.isLookalike()) canonical(char) else char)
        return out.toString()
    }

    /** Whether [text] contains any of them, for the places that only need to ask. */
    fun hasLookalikes(text: String): Boolean = text.any { it.isLookalike() }
}
