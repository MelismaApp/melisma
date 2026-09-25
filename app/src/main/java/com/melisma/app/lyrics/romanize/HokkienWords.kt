package com.melisma.app.lyrics.romanize

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.Normalizer

/**
 * Taiwanese Hokkien in Tâi-lô, by word.
 *
 * Hokkien is written with the same characters as Mandarin and read completely differently: 原來 is
 * *guân-lâi*, not *yuánlái*, and 人 is *lâng* in speech and *jîn* in literary words. So the reading
 * comes from a word table first and a per-character default only where no word covers a character,
 * as for Mandarin — but Android has no Hokkien transliterator to fall back on, so the characters have
 * a table of their own too, and the word table has to hold every word rather than only the exceptions.
 *
 * Words are also where Tâi-lô puts its spaces: syllables of one word are joined by hyphens, and words
 * are separated by spaces. A word the character defaults already read correctly is listed without a
 * reading for exactly that reason.
 *
 * Lyrics sent in Simplified characters are looked up under a folded copy of the tables, built the
 * first time one appears. Traditional lyrics, which is how most Hokkien songs are written, never pay
 * for it.
 *
 * The Ministry of Education's dictionary, once downloaded, is read before the bundled words; see
 * [useDictionary].
 *
 * The data and its licences are in `tools/hokkien/build_tables.py` and `docs/ROMANIZATION.md`.
 */
object HokkienWords {

    /** A character's syllable, and how it joins the one before: "" starts a word, "-" or "--" does not. */
    class Reading(val syllable: String, val join: String)

    private class Tables(
        /** An empty reading means the character defaults, hyphenated. */
        val words: Map<String, String>,
        val chars: Map<String, String>,
        val fold: Map<Char, Char>,
        val poj: Map<String, String>,
        val longest: Int,
    )

    private val tables: Tables by lazy { load() }

    private class Folded(val words: Map<String, String>, val chars: Map<String, String>)

    private val folded: Folded by lazy { buildFolded() }

    /** No word in the table is longer than this, and none of the few that were is in a song. */
    private const val LONGEST_WORD = 8

    /** The words a downloaded dictionary reads differently from the bundled tables, or adds. */
    private class Dictionary(val words: Map<String, String>, val longest: Int) {
        @Volatile var folded: Map<String, String>? = null
    }

    @Volatile private var dictionary: Dictionary? = null

    private val changes = MutableStateFlow(0)

    /** Changes whenever a dictionary is added or removed, so readings already worked out are redone. */
    val revision: StateFlow<Int> = changes

    /**
     * Read words through [entries] first: each word's readings, in the dictionary's order. A word the
     * bundled tables already read as one of them keeps that reading — the dictionary lists 大人 as both
     * *tāi-jîn* and *tuā-lâng*, and only disagreeing with it is a reason to change — and otherwise the
     * dictionary's first wins. Single characters are left alone: a dictionary's first reading of one
     * is its literary one as often as not, and a song needs the colloquial. Null removes it.
     *
     * Returns how many words now read differently or were added.
     */
    fun useDictionary(entries: Map<String, List<String>>?): Int {
        if (entries == null) {
            dictionary = null
            changes.update { it + 1 }
            return 0
        }
        val t = tables
        val words = LinkedHashMap<String, String>(entries.size * 2)
        var longest = 1
        for ((word, readings) in entries) {
            val length = word.codePointCount(0, word.length)
            if (length < 2 || length > LONGEST_WORD) continue
            val usable = readings.filter { parse(it, length) != null }
            val first = usable.firstOrNull() ?: continue
            val own = t.words[word]?.let { it.ifEmpty { compositional(word, t.chars) } }
            if (own != null && usable.any { sameReading(it, own) }) continue
            words[word] = first
            if (length > longest) longest = length
        }
        dictionary = Dictionary(words, longest)
        changes.update { it + 1 }
        return words.size
    }

    /** A space between words and a hyphen within one are the same syllables. */
    private fun sameReading(a: String, b: String): Boolean = a.replace(' ', '-') == b.replace(' ', '-')

    private fun foldedWords(extra: Dictionary): Map<String, String> =
        extra.folded ?: foldKeys(extra.words) { it.value }.also { extra.folded = it }

    /**
     * [readings] keyed by their Simplified form. A key that is already Simplified wins its slot, as
     * for the characters: 巨大 stays *kī-tāi* rather than taking 鉅大's *kú-tuā*. Otherwise the first
     * in [readings]' order.
     */
    private inline fun foldKeys(readings: Map<String, String>, resolve: (Map.Entry<String, String>) -> String?): Map<String, String> {
        val out = HashMap<String, String>(readings.size * 2)
        for (entry in readings) if (fold(entry.key) == entry.key) resolve(entry)?.let { out[entry.key] = it }
        for (entry in readings) {
            val key = fold(entry.key)
            if (key != entry.key && key !in out) resolve(entry)?.let { out[key] = it }
        }
        return out
    }

    private fun lines(resource: String, each: (String) -> Unit) {
        val stream = HokkienWords::class.java.getResourceAsStream(resource) ?: return
        runCatching { stream.bufferedReader().use { reader -> reader.forEachLine(each) } }
    }

    private fun load(): Tables {
        // In file order, so which of two Traditional spellings fills a Simplified slot is always the same.
        val words = LinkedHashMap<String, String>(1 shl 17)
        var longest = 1
        lines("/hokkien/words.txt") { line ->
            val tab = line.indexOf('\t')
            val word = if (tab < 0) line else line.substring(0, tab)
            val length = word.codePointCount(0, word.length)
            if (word.isEmpty() || length > LONGEST_WORD) return@lines
            words[word] = if (tab < 0) "" else line.substring(tab + 1)
            if (length > longest) longest = length
        }
        val chars = HashMap<String, String>(1 shl 13)
        lines("/hokkien/chars.txt") { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) chars[line.substring(0, tab)] = line.substring(tab + 1)
        }
        val fold = HashMap<Char, Char>(1 shl 12)
        lines("/hokkien/fold.txt") { line ->
            if (line.length == 3 && line[1] == '\t') fold[line[0]] = line[2]
        }
        val poj = HashMap<String, String>(1 shl 12)
        lines("/hokkien/poj.txt") { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) poj[line.substring(0, tab)] = line.substring(tab + 1)
        }
        return Tables(words, chars, fold, poj, longest)
    }

    /** Whether the tables loaded at all. */
    val isAvailable: Boolean get() = tables.chars.isNotEmpty()

    /** [text] with each Traditional character replaced by its Simplified form. Same length. */
    fun fold(text: String): String {
        val map = tables.fold
        val out = CharArray(text.length) { map[text[it]] ?: text[it] }
        return String(out)
    }

    private fun buildFolded(): Folded {
        val t = tables
        val words = foldKeys(t.words) { (word, reading) -> reading.ifEmpty { compositional(word, t.chars) } }
        // A character that is already its own Simplified form wins its slot: 干 keeps its reading
        // rather than taking whichever of 乾 and 幹 came first.
        val chars = HashMap<String, String>(t.chars.size * 2)
        for ((char, reading) in t.chars) if (fold(char) == char) chars[char] = reading
        for ((char, reading) in t.chars) chars.putIfAbsent(fold(char), reading)
        return Folded(words, chars)
    }

    private fun compositional(word: String, chars: Map<String, String>): String? {
        val out = StringBuilder()
        var index = 0
        while (index < word.length) {
            val width = Character.charCount(word.codePointAt(index))
            val reading = chars[word.substring(index, index + width)] ?: return null
            if (out.isNotEmpty()) out.append('-')
            out.append(reading)
            index += width
        }
        return out.toString()
    }

    /**
     * A reading per character of [text], at the index of the character's first UTF-16 unit, or null
     * where there is none. Null overall when nothing in [text] has a reading.
     */
    fun readings(text: String): Array<Reading?>? {
        val t = tables
        if (t.chars.isEmpty() || text.isEmpty()) return null

        // Simplified lyrics have characters the Traditional tables do not, and are read through the
        // folded ones instead — all of the line, so one word is not split between two spellings.
        val simplified = hasSimplified(text, t)
        val words = if (simplified) folded.words else t.words
        val chars = if (simplified) folded.chars else t.chars
        val key = if (simplified) fold(text) else text
        val extra = dictionary
        val extraWords = extra?.let { if (simplified) foldedWords(it) else it.words }
        val longest = maxOf(t.longest, extra?.longest ?: 0)

        val starts = ArrayList<Int>(key.length + 1)
        var at = 0
        while (at < key.length) {
            starts += at
            at += Character.charCount(key.codePointAt(at))
        }
        starts += key.length

        val out = arrayOfNulls<Reading>(text.length)
        var found = false
        var i = 0
        val count = starts.size - 1
        while (i < count) {
            var matched = 1
            var size = minOf(longest, count - i)
            while (size >= 2) {
                val word = key.substring(starts[i], starts[i + size])
                val reading = extraWords?.get(word) ?: words[word]
                if (reading != null) {
                    val parsed = parse(reading.ifEmpty { compositional(word, chars) ?: "" }, size)
                    if (parsed != null) {
                        for (k in 0 until size) out[starts[i + k]] = parsed[k]
                        matched = size
                        found = true
                    }
                    break
                }
                size--
            }
            if (matched == 1 && out[starts[i]] == null) {
                chars[key.substring(starts[i], starts[i + 1])]?.let {
                    out[starts[i]] = Reading(it, "")
                    found = true
                }
            }
            i += matched
        }
        return if (found) out else null
    }

    private fun hasSimplified(text: String, t: Tables): Boolean {
        var index = 0
        while (index < text.length) {
            val width = Character.charCount(text.codePointAt(index))
            val char = text.substring(index, index + width)
            if (width == 1 && isHan(text[index]) && t.chars[char] == null && folded.chars[char] != null) return true
            index += width
        }
        return false
    }

    private fun isHan(char: Char): Boolean =
        char in '一'..'鿿' || char in '㐀'..'䶿' || char in '豈'..'﫿'

    private val separator = Regex("--|-| ")

    /** A stored reading as one [Reading] per character, or null if it does not have [size] syllables. */
    private fun parse(reading: String, size: Int): List<Reading>? {
        if (reading.isEmpty()) return null
        val out = ArrayList<Reading>(size)
        var join = ""
        var last = 0
        for (match in separator.findAll(reading)) {
            out += Reading(reading.substring(last, match.range.first), join)
            join = if (match.value == " ") "" else match.value
            last = match.range.last + 1
        }
        out += Reading(reading.substring(last), join)
        return out.takeIf { it.size == size && it.none { r -> r.syllable.isEmpty() } }
    }

    /**
     * [text] in Tâi-lô, or POJ when [poj] is set: syllables of a word hyphenated, words spaced, and
     * anything without a reading left as it was. Null when nothing had a reading.
     */
    fun romanize(text: String, poj: Boolean = false): String? {
        val byChar = readings(text) ?: return null
        val out = StringBuilder(text.length * 5)
        var afterSyllable = false
        var index = 0
        while (index < text.length) {
            val width = Character.charCount(text.codePointAt(index))
            val reading = byChar[index]
            if (reading != null) {
                if (reading.join.isNotEmpty() && afterSyllable) {
                    out.append(reading.join)
                } else if (out.isNotEmpty() && !out.last().isWhitespace() && !opensGroup(out.last())) {
                    out.append(' ')
                }
                out.append(spell(reading.syllable, poj))
                afterSyllable = true
            } else {
                val raw = text.substring(index, index + width)
                if (afterSyllable && raw[0].isLetterOrDigit()) out.append(' ')
                out.append(raw)
                afterSyllable = false
            }
            index += width
        }
        return out.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun opensGroup(char: Char): Boolean = char in "([{「『（【《“‘"

    /** One Tâi-lô syllable, in POJ if asked. */
    fun spell(syllable: String, poj: Boolean): String =
        if (poj) tables.poj[syllable] ?: TaiLo.toPoj(syllable) else syllable

    /**
     * Tone marks off, for "drop tone marks". POJ's o͘ keeps its dot: it is part of the vowel, not a
     * tone, and without it *o͘* and *o* are the same letter.
     */
    fun stripTones(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val kept = StringBuilder(decomposed.length)
        for (char in decomposed) {
            if (char == '\u0358' || Character.getType(char) != Character.NON_SPACING_MARK.toInt()) kept.append(char)
        }
        return Normalizer.normalize(kept, Normalizer.Form.NFC)
    }
}

/**
 * Tâi-lô to POJ by rule, for the few syllables the dictionary never spells in POJ itself.
 *
 * The letters convert one to one (ts → ch, ua → oa, oo → o͘, a final nn → ⁿ …). The tone mark then has
 * to move, because the two systems put it on different vowels: POJ marks the o of an open oa or oe,
 * and otherwise the first of a, e, o, u, i.
 */
internal object TaiLo {

    private val tones = setOf('\u0301', '\u0300', '\u0302', '\u0304', '\u030d', '\u030c', '\u0306')

    fun toPoj(syllable: String): String {
        val decomposed = Normalizer.normalize(syllable, Normalizer.Form.NFD)
        val mark = decomposed.firstOrNull { it in tones }
        var base = decomposed.filterNot { it in tones }
        base = base.replace("tsh", "chh").replace("ts", "ch")
            .replace("ua", "oa").replace("ue", "oe")
            .replace("ing", "eng").replace("ik", "ek")
            .replace("oo", "o\u0358")
        if (base.endsWith("nn")) base = base.dropLast(2) + "\u207f"
        if (mark == null) return Normalizer.normalize(base, Normalizer.Form.NFC)

        val at = markPosition(base)
        if (at < 0) return Normalizer.normalize(base, Normalizer.Form.NFC)
        val marked = base.substring(0, at + 1) + mark + base.substring(at + 1)
        return Normalizer.normalize(marked, Normalizer.Form.NFC)
    }

    private fun markPosition(base: String): Int {
        val letters = base.replace("\u0358", "")
        val open = Regex("o[ae]\u207f?$").containsMatchIn(letters) || Regex("o[ae]h?$").containsMatchIn(letters)
        if (open) return base.indexOf('o')
        for (vowel in "aeoui") {
            val index = base.indexOf(vowel)
            if (index >= 0) return index
        }
        // A syllable with no vowel is a syllabic nasal, and carries the mark on it.
        val nasal = base.indexOfFirst { it == 'm' || it == 'n' }
        return if (nasal >= 0 && base.startsWith("ng")) 0 else nasal
    }
}
