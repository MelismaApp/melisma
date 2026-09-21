package com.melisma.app.util

/**
 * Which writing system a chunk of lyrics is in — the input to both romanization
 * and translation. Deliberately character-range based rather than statistical: song
 * lyrics are short, and a single line of kana is all the evidence we need.
 */
enum class Script { JAPANESE, CHINESE, KOREAN, CYRILLIC, GREEK, LATIN, OTHER }

private fun Char.isHiraganaOrKatakana(): Boolean =
    this in '぀'..'ゟ' || this in '゠'..'ヿ' || this in 'ㇰ'..'ㇿ'

private fun Char.isHan(): Boolean =
    this in '一'..'鿿' || this in '㐀'..'䶿' || this in '豈'..'﫿'

private fun Char.isHangul(): Boolean =
    this in '가'..'힣' || this in 'ᄀ'..'ᇿ' ||
        this in '㄰'..'㆏' || this in 'ꥠ'..'꥿' ||
        this in 'ힰ'..'퟿'

private fun Char.isCyrillic(): Boolean =
    this in 'Ѐ'..'ӿ' || this in 'Ԁ'..'ԯ' ||
        this in 'ⷠ'..'ⷿ' || this in 'Ꙁ'..'ꚟ'

private fun Char.isGreek(): Boolean =
    this in 'Ͱ'..'Ͽ' || this in 'ἀ'..'῿'

// The ranges are written as escapes rather than as literals: the last one ends at the
// byte-order mark, which is invisible in an editor and which tooling reads as a file
// marker rather than as a character.
private fun Char.isRtlChar(): Boolean =
    this in '\u0590'..'\u05FF' || // Hebrew
        this in '\u0600'..'\u06FF' || // Arabic
        this in '\u0700'..'\u074F' || // Syriac
        this in '\u0750'..'\u077F' || // Arabic Supplement
        this in '\u08A0'..'\u08FF' || // Arabic Extended-A
        this in '\uFB1D'..'\uFDFF' || // Hebrew and Arabic presentation forms
        this in '\uFE70'..'\uFEFF' // Arabic Presentation Forms-B

/** True when the line should be laid out right-to-left. */
fun String.isRtlText(): Boolean {
    var rtl = 0
    var latin = 0
    for (c in this) {
        when {
            c.isRtlChar() -> rtl++
            c.isLetter() -> latin++
        }
    }
    return rtl > 0 && rtl >= latin
}

/**
 * The dominant romanizable script in [text].
 *
 * CJK is resolved to exactly one of Japanese / Chinese — kana anywhere means the
 * kanji are Japanese and must be read with Japanese readings, never pinyin. This is
 * the same rule Spicy Lyrics applies before it picks a romanizer.
 */
fun detectScript(text: String): Script {
    var kana = 0
    var han = 0
    var hangul = 0
    var cyrillic = 0
    var greek = 0
    var latin = 0

    for (c in text) {
        when {
            c.isHiraganaOrKatakana() -> kana++
            c.isHan() -> han++
            c.isHangul() -> hangul++
            c.isCyrillic() -> cyrillic++
            c.isGreek() -> greek++
            c.code < 0x250 && c.isLetter() -> latin++
        }
    }

    return when {
        kana > 0 -> Script.JAPANESE
        hangul > 0 && hangul >= han -> Script.KOREAN
        han > 0 -> Script.CHINESE
        hangul > 0 -> Script.KOREAN
        cyrillic >= 2 -> Script.CYRILLIC
        greek >= 2 -> Script.GREEK
        latin > 0 -> Script.LATIN
        else -> Script.OTHER
    }
}

/** A stretch of text written in one script, and where it sits in the line it came from. */
data class ScriptRun(val script: Script, val text: String, val start: Int)

/**
 * Which script a single character belongs to, or null for one that belongs to no script at all.
 *
 * Spaces, punctuation and digits are the null case, and they are deliberately not a script of their
 * own: a comma between two Korean words is part of that Korean run, and making it a boundary would
 * chop every line into a dozen fragments for the romanizer to guess at separately.
 *
 * @param hanScript how to read a Han character. Han is genuinely ambiguous — 燃 is *moeru* in a
 *   Japanese song and *rán* in a Chinese one — and nothing about the character itself decides which.
 *   Only the wider text does, which is why this has to be told.
 */
private fun Char.scriptOrNull(hanScript: Script): Script? = when {
    isHiraganaOrKatakana() -> Script.JAPANESE
    isHan() -> hanScript
    isHangul() -> Script.KOREAN
    isCyrillic() -> Script.CYRILLIC
    isGreek() -> Script.GREEK
    code < 0x250 && isLetter() -> Script.LATIN
    else -> null
}

/**
 * Split [text] into consecutive runs, each written in one script.
 *
 * This exists because [detectScript] answers a different question than romanization asks. It returns
 * the *dominant* script, which is right for choosing a translator or comparing two artist names — and
 * wrong for a song that changes language partway. "Chasing Lightning" is Korean and Japanese in the
 * same breath; one kana anywhere in it made `detectScript` say Japanese for the whole song, so every
 * Korean line went to a Japanese dictionary that had nothing to say about Hangul and came back
 * unchanged. Half a song romanized, and no error anywhere to explain the other half.
 *
 * Runs rather than characters because the engines need them: a Japanese reading depends on the word
 * around the character, so 燃えろ has to arrive at the analyser whole.
 */
fun scriptRuns(text: String, hanScript: Script = Script.CHINESE): List<ScriptRun> {
    if (text.isEmpty()) return emptyList()

    val runs = ArrayList<ScriptRun>(2)
    var current: Script? = null
    var start = 0

    for ((index, c) in text.withIndex()) {
        val script = c.scriptOrNull(hanScript) ?: continue
        if (current == null) {
            current = script
            continue
        }
        if (script != current) {
            runs += ScriptRun(current, text.substring(start, index), start)
            current = script
            start = index
        }
    }

    // Nothing in the whole string belongs to a script: punctuation, digits, an instrumental marker.
    val last = current ?: return listOf(ScriptRun(Script.OTHER, text, 0))
    runs += ScriptRun(last, text.substring(start), start)
    return runs
}

/**
 * True when any part of [text] is written in something worth romanizing.
 *
 * The test to use before offering romanization at all, in place of asking whether the *dominant*
 * script needs it — a mostly-English song with a Korean chorus needs it, and the dominant script
 * would say no.
 */
fun containsRomanizableScript(text: String, hanScript: Script = Script.CHINESE): Boolean =
    text.any { it.scriptOrNull(hanScript)?.needsRomanization() == true }

/** Best-effort BCP-47 tag for a script, for handing to a translator. */
fun Script.languageTag(): String? = when (this) {
    Script.JAPANESE -> "ja"
    Script.CHINESE -> "zh"
    Script.KOREAN -> "ko"
    Script.CYRILLIC -> "ru"
    Script.GREEK -> "el"
    Script.LATIN, Script.OTHER -> null
}

fun Script.needsRomanization(): Boolean = when (this) {
    Script.JAPANESE, Script.CHINESE, Script.KOREAN, Script.CYRILLIC, Script.GREEK -> true
    Script.LATIN, Script.OTHER -> false
}

/**
 * True when [text] is written in a script that runs without spaces between words.
 *
 * Matters for romanization: the original has no word gaps to inherit, so the Latin
 * transcription of consecutive syllables would otherwise read as `kiminokoega`.
 */
fun isSpacelessScript(text: String): Boolean = text.any { c ->
    c.isHiraganaOrKatakana() || c.isHan() || c.isHangul()
}

/**
 * True when [text] contains at least one Latin letter.
 *
 * The test for whether two names can be compared letter by letter: `YOASOBI (ヨアソビ)`
 * and `YOASOBI` can, `米津玄師` and `Kenshi Yonezu` cannot.
 */
fun hasLatinLetters(text: String): Boolean = text.any { it.code < 0x250 && it.isLetter() }

/** True when [text] still contains characters the romanizer was supposed to convert. */
fun containsNonLatinScript(text: String): Boolean = text.any { c ->
    c.isHiraganaOrKatakana() || c.isHan() || c.isHangul() || c.isCyrillic() || c.isGreek()
}
