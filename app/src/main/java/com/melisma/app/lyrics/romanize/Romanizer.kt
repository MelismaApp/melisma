package com.melisma.app.lyrics.romanize

import android.icu.text.Transliterator
import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.Syllable
import com.melisma.app.settings.ChineseReading
import com.melisma.app.settings.HokkienSpelling
import com.melisma.app.util.HanCanonical
import com.melisma.app.util.Script
import com.melisma.app.util.containsNonLatinScript
import com.melisma.app.util.containsRomanizableScript
import com.melisma.app.util.detectScript
import com.melisma.app.util.scriptRuns
import com.melisma.app.util.needsRomanization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Turns non-Latin lyrics into something you can actually sing.
 *
 * Two engines, picked by script:
 *
 * - **Japanese** goes through Kuromoji, because kanji have no fixed reading — 生 is
 *   *ki*, *sei*, *nama* or *i* depending on the word around it. Only a morphological
 *   analyser gets this right, and it is the single reason the app carries a
 *   dictionary. Notably, running kanji through a Han transliterator instead would
 *   produce Mandarin readings, which is wrong in a way that is hard to notice.
 * - **Everything else** (Hangul, Han, Cyrillic, Greek) uses the ICU transliterators
 *   built into Android, which are per-character and need no context.
 * - **Taiwanese Hokkien** is a third reading of Han, after Japanese and Mandarin, and like them is
 *   decided for the whole song: see [HokkienDetector] and [HokkienWords].
 *
 * A romanization the provider already shipped is never overwritten — a human-checked
 * `romalrc` beats anything generated here.
 */
class Romanizer {

    private val tokenizerLock = Mutex()

    @Volatile
    private var tokenizer: Tokenizer? = null

    private val transliterators = HashMap<String, Transliterator?>()

    /** Drops tone marks and other diacritics, for people who find `nǐ hǎo` harder to read. */
    private val diacriticStripper: Transliterator? by lazy {
        runCatching {
            Transliterator.getInstance("NFD; [:Nonspacing Mark:] Remove; NFC")
        }.getOrNull()
    }

    /**
     * Fills in whichever annotations the reader asked for.
     *
     * One pass rather than two, because both come out of the same analysis: the romaji and
     * the kana gloss for 君 are two readings of one dictionary lookup. Furigana is
     * Japanese-only by definition, so [furigana] is ignored for every other script.
     */
    suspend fun annotate(
        document: LyricsDocument,
        romanize: Boolean,
        furigana: Boolean,
        stripDiacritics: Boolean = false,
        /** How to read Chinese; [ChineseReading.AUTO] asks [HokkienDetector]. */
        chinese: ChineseReading = ChineseReading.MANDARIN,
        hokkienSpelling: HokkienSpelling = HokkienSpelling.TAILO,
    ): LyricsDocument = withContext(Dispatchers.Default) {
        if (!romanize && !furigana) return@withContext document

        val corpus = document.lines.joinToString("\n") { it.text }

        // The one thing that *is* decided for the whole song, because only the whole song can decide
        // it: how to read a Han character. 燃 is moeru in a Japanese lyric and rán in a Chinese one,
        // and kana anywhere is what settles it — the rule Spicy Lyrics uses too. Everything else is
        // decided per line and per run, because a song is allowed to change language and this one
        // used to romanize only whichever half won the vote.
        val hanScript = if (detectScript(corpus) == Script.JAPANESE) {
            Script.JAPANESE
        } else {
            Script.CHINESE
        }
        // Kana present is exactly what made Han Japanese above, so it is also the test for whether
        // there is any Japanese here at all — kanji on its own is read as Chinese.
        val anyJapanese = hanScript == Script.JAPANESE

        val wantFurigana = furigana && anyJapanese
        val wantRomaji = romanize && containsRomanizableScript(corpus, hanScript)
        if (!wantRomaji && !wantFurigana) return@withContext document

        if (anyJapanese) prepareTokenizer()

        // Hokkien is decided once, for the song, like Japanese above: most of its lines could be
        // either, and only the rest of the song can say which.
        val hokkien = hokkienSpelling.takeIf {
            wantRomaji && hanScript == Script.CHINESE && HokkienWords.isAvailable && when (chinese) {
                ChineseReading.HOKKIEN -> true
                ChineseReading.MANDARIN -> false
                ChineseReading.AUTO -> HokkienDetector.isHokkien(document.lines.map { it.text })
            }
        }

        val held = heldFor(document)

        var produced = false
        val lines = document.lines.map { line ->
            if (line.isInterlude || line.text.isBlank()) return@map line
            val annotated = annotateLine(line, hanScript, stripDiacritics, wantRomaji, hokkien, held)
            if (annotated !== line) produced = true
            annotated
        }

        if (!produced) return@withContext document
        document.copy(
            lines = lines,
            hasRomanization = document.hasRomanization || wantRomaji,
            // Still the dominant script: a language tag names one language, and a translator asked
            // about a bilingual song has to be told something.
            language = document.language ?: scriptLanguage(detectScript(corpus)),
        )
    }

    private fun scriptLanguage(script: Script): String? = when (script) {
        Script.JAPANESE -> "ja"
        Script.CHINESE -> "zh"
        Script.KOREAN -> "ko"
        Script.CYRILLIC -> "ru"
        Script.GREEK -> "el"
        else -> null
    }

    private fun annotateLine(
        line: LyricLine,
        hanScript: Script,
        stripDiacritics: Boolean,
        wantRomaji: Boolean,
        hokkien: HokkienSpelling? = null,
        held: Int = Int.MAX_VALUE,
    ): LyricLine {
        // What a provider ships for a Hokkien song is its own spelling, pinyin-like and without tones,
        // where there is one at all; Tâi-lô replaces it, so the whole song is spelled one way.
        val replacing = hokkien != null && line.text.any { it.isHanCharacter() }

        if (line.syllables.isEmpty()) {
            if (!wantRomaji || (!replacing && !line.romanized.isNullOrBlank())) return line
            val text = romanizeMixed(line.text, hanScript, stripDiacritics, hokkien) ?: return line
            return line.copy(romanized = text)
        }

        val syllables = if (hokkien != null && wantRomaji) {
            hokkienSyllables(line.syllables, stripDiacritics, hokkien)
        } else {
            annotateSyllables(line.syllables, hanScript, stripDiacritics, wantRomaji, held)
        } ?: return line
        if (!wantRomaji) return line.copy(syllables = syllables)

        val joined = syllables.joinToString("") { syllable ->
            val part = syllable.romanized ?: syllable.text
            val startsWord = syllable.romanizedStartsWord
            val continues = if (hokkien != null && startsWord != null) !startsWord else syllable.partOfWord
            (if (continues) "" else " ") + part
        }
            // A syllable that is itself a space contributes one, and the join adds another either
            // side of it — so a line with a space between two words came out with three.
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        return line.copy(
            syllables = syllables,
            romanized = if (replacing) joined else line.romanized ?: joined,
        )
    }

    /**
     * Tâi-lô for a line's timed syllables, read as one line so a word can span them.
     *
     * A syllable that continues a word carries the hyphen at its front and says it does not start a
     * word, so the renderer draws `guân` and `-lâi` side by side as *guân-lâi*. A syllable with no
     * Chinese in it — a Korean or English phrase in the song — is romanized as its own script is.
     */
    private fun hokkienSyllables(
        syllables: List<Syllable>,
        stripDiacritics: Boolean,
        spelling: HokkienSpelling,
    ): List<Syllable>? {
        val lineText = syllables.joinToString("") { it.text }
        val lookup = HanCanonical.of(lineText)
        val byChar = HokkienWords.readings(lookup)
        val poj = spelling == HokkienSpelling.POJ

        var changed = false
        var offset = 0
        val out = syllables.map { syllable ->
            val start = offset
            val end = start + syllable.text.length
            offset = end
            if (syllable.text.none { it.isHanCharacter() } || byChar == null) {
                if (!syllable.romanized.isNullOrBlank() || syllable.text.any { it.isHanCharacter() }) return@map syllable
                val other = romanizeMixed(syllable.text, Script.CHINESE, stripDiacritics) ?: return@map syllable
                changed = true
                return@map syllable.copy(romanized = other)
            }

            val text = StringBuilder()
            var first: HokkienWords.Reading? = null
            var index = start
            while (index < end) {
                val width = Character.charCount(lookup.codePointAt(index))
                val reading = byChar[index]
                if (reading == null) {
                    text.append(lineText, index, index + width)
                } else {
                    if (first == null) first = reading
                    if (text.isNotEmpty()) text.append(reading.join.ifEmpty { " " })
                    text.append(HokkienWords.spell(reading.syllable, poj))
                }
                index += width
            }
            val lead = first ?: return@map syllable
            val spelled = text.toString().trim().let { if (stripDiacritics) HokkienWords.stripTones(it) else it }
            val continues = lead.join.isNotEmpty() && start > 0
            changed = true
            syllable.copy(
                romanized = if (continues) lead.join + spelled else spelled,
                romanizedStartsWord = !continues,
            )
        }
        return if (changed) out else null
    }

    /**
     * Romanize a line's syllables while keeping the one-to-one mapping the renderer
     * needs — every syllable must come out with its own text, or the karaoke wipe has
     * nothing to fill.
     */
    private fun annotateSyllables(
        syllables: List<Syllable>,
        hanScript: Script,
        stripDiacritics: Boolean,
        wantRomaji: Boolean,
        held: Int = Int.MAX_VALUE,
    ): List<Syllable>? {
        val lineText = syllables.joinToString("") { it.text }
        val japanese = hanScript == Script.JAPANESE &&
            scriptRuns(lineText, hanScript).any { it.script == Script.JAPANESE }

        if (!japanese) {
            if (!wantRomaji) return null

            // A Chinese reading is decided by the word, and a word spans syllables — 音 and 乐 arrive
            // separately and neither of them is 音乐 — so the line is read whole and each syllable
            // takes the readings for its own characters. Without this the word table could never
            // fire at all, which is the real reason a per-character reading was all there was.
            // Same length by construction, so a reading still lands on the character it came
            // from. What the provider sent is still what gets drawn.
            val lookupText = HanCanonical.of(lineText)
            val byWord = if (hanScript == Script.CHINESE) PinyinWords.readings(lookupText) else null

            // Per-character scripts otherwise: each syllable converts independently, and by its own
            // script rather than the song's, so a Korean line inside a Japanese song reads as Korean.
            var changed = false
            var offset = 0
            val out = syllables.map { syllable ->
                val start = offset
                offset += syllable.text.length
                if (!syllable.romanized.isNullOrBlank()) return@map syllable
                val romanized = byWord
                    ?.let { spanReading(it, lookupText, start, syllable.text.length) }
                    // The table stores tone marks, and this path does not go through `romanizeText`,
                    // so "drop tone marks" has to be honoured here too or the setting would apply to
                    // some syllables of a line and not others.
                    ?.let { if (stripDiacritics) stripDiacritics(it) else it }
                    ?: romanizeMixed(syllable.text, hanScript, stripDiacritics)
                    ?: return@map syllable
                changed = true
                syllable.copy(romanized = if (hanScript == Script.CHINESE) sungLiao(syllable, romanized, held, stripDiacritics) else romanized)
            }
            return if (changed) out else null
        }

        val tokenizer = this.tokenizer ?: return null

        // Reconstruct the line exactly as written so the analyser sees real words,
        // and remember where each syllable sits inside it.
        val builder = StringBuilder()
        val ranges = ArrayList<IntRange>(syllables.size)
        for (syllable in syllables) {
            val start = builder.length
            builder.append(syllable.text)
            ranges += start until builder.length
        }
        val text = builder.toString()
        if (text.isBlank()) return null

        val tokens = runCatching { tokenizer.tokenize(text) }.getOrNull() ?: return null
        val spans = tokens.map { token ->
            TokenSpan(
                start = token.position,
                end = token.position + token.surface.length,
                romaji = token.romaji(),
                kana = token.kanaReading(),
            )
        }

        var changed = false
        val out = syllables.mapIndexed { index, syllable ->
            val range = ranges[index]
            val kana = syllable.kana ?: kanaFor(range, spans, syllable.text)

            if (!wantRomaji || !syllable.romanized.isNullOrBlank()) {
                if (kana == syllable.kana) return@mapIndexed syllable
                changed = true
                return@mapIndexed syllable.copy(kana = kana)
            }

            val romanized = romajiFor(range, spans).trim()
            if (romanized.isEmpty()) {
                if (kana == syllable.kana) return@mapIndexed syllable
                changed = true
                return@mapIndexed syllable.copy(kana = kana)
            }
            changed = true
            syllable.copy(
                romanized = romanized,
                // A syllable that a token starts at is the start of a word; one that
                // merely continues a token is the middle of one.
                romanizedStartsWord = spans.any { it.start == range.first },
                kana = kana,
            )
        }
        if (!wantRomaji) return if (changed) out else null

        // Second pass, for the parts of a bilingual line the analyser had nothing to say about.
        // Kuromoji hands back the surface form of a word it does not know, so a Hangul syllable in a
        // Japanese song comes out of the pass above still written in Hangul — which is precisely the
        // half of "Chasing Lightning" that never got romanized. Anything still in another script gets
        // a second go with the engine for whatever script it is actually in.
        var fixed = false
        val patched = out.map { syllable ->
            if (!needsAnotherPass(syllable.romanized)) return@map syllable
            val romanized = romanizeMixed(syllable.text, hanScript, stripDiacritics)
                ?: return@map syllable
            fixed = true
            syllable.copy(
                romanized = romanized,
                // A foreign run inside a Japanese line is its own word, whatever the analyser made
                // of the characters around it.
                romanizedStartsWord = true,
            )
        }
        return when {
            fixed -> patched
            changed -> out
            else -> null
        }
    }

    private class TokenSpan(
        val start: Int,
        val end: Int,
        val romaji: String,
        /** Katakana reading, for furigana. Null when the surface is already kana. */
        val kana: String?,
    )

    /**
     * The romaji covering [range].
     *
     * A token usually lines up with a syllable, but not always: `食べて` may arrive as
     * three timed syllables inside one token whose reading (`tabete`) is only defined
     * for the whole thing. When that happens the reading is split across the syllables
     * in proportion to how many characters each covers — approximate per syllable, but
     * it never drops or duplicates a sound across the line, which is what would
     * actually look broken.
     */
    private fun romajiFor(range: IntRange, spans: List<TokenSpan>): String {
        val builder = StringBuilder()
        for (span in spans) {
            if (span.end <= range.first || span.start > range.last) continue
            val spanLength = span.end - span.start
            if (spanLength <= 0 || span.romaji.isEmpty()) continue

            val overlapStart = maxOf(range.first, span.start)
            val overlapEnd = minOf(range.last + 1, span.end)
            if (overlapStart >= overlapEnd) continue

            if (overlapStart == span.start && overlapEnd == span.end) {
                builder.append(span.romaji)
            } else {
                val from = ((overlapStart - span.start).toFloat() / spanLength *
                    span.romaji.length).toInt().coerceIn(0, span.romaji.length)
                val to = ((overlapEnd - span.start).toFloat() / spanLength *
                    span.romaji.length).toInt().coerceIn(from, span.romaji.length)
                builder.append(span.romaji, from, to)
            }
        }
        return builder.toString()
    }

    /**
     * The kana gloss for [range], or null when it needs none.
     *
     * A syllable that is already kana is left alone — printing おも over 思 is useful,
     * printing う over う is noise. A kanji syllable that sits inside a longer token
     * takes a proportional slice of that token's reading, on the same basis as the
     * romaji: approximate per syllable, but never losing or repeating a sound.
     */
    private fun kanaFor(range: IntRange, spans: List<TokenSpan>, surface: String): String? {
        if (!surface.any { it.isHanCharacter() }) return null

        val builder = StringBuilder()
        for (span in spans) {
            if (span.end <= range.first || span.start > range.last) continue
            val reading = span.kana ?: continue
            val spanLength = span.end - span.start
            if (spanLength <= 0 || reading.isEmpty()) continue

            val overlapStart = maxOf(range.first, span.start)
            val overlapEnd = minOf(range.last + 1, span.end)
            if (overlapStart >= overlapEnd) continue

            if (overlapStart == span.start && overlapEnd == span.end) {
                builder.append(reading)
            } else {
                val from = ((overlapStart - span.start).toFloat() / spanLength *
                    reading.length).toInt().coerceIn(0, reading.length)
                val to = ((overlapEnd - span.start).toFloat() / spanLength *
                    reading.length).toInt().coerceIn(from, reading.length)
                builder.append(reading, from, to)
            }
        }
        return builder.toString().trim().takeIf { it.isNotEmpty() && it != surface }
    }

    private fun Char.isHanCharacter(): Boolean =
        this in '\u4e00'..'\u9fff' || this in '\u3400'..'\u4dbf' || this in '\uf900'..'\ufaff'

    /** Katakana reading straight from the dictionary, or null if there is nothing to add. */
    private fun Token.kanaReading(): String? =
        (reading ?: pronunciation)?.takeIf { it.isNotBlank() && it != "*" && it != surface }

    private fun Token.romaji(): String {
        val reading = pronunciation?.takeIf { it.isNotBlank() && it != "*" }
            ?: this.reading?.takeIf { it.isNotBlank() && it != "*" }
        return when {
            reading != null -> Romaji.fromKana(reading)
            Romaji.isPureKana(surface) -> Romaji.fromKana(surface)
            // Unknown word (often a foreign name in katakana already handled above, or
            // punctuation): leave it as it is rather than inventing a reading.
            else -> surface
        }
    }

    /**
     * Romanize text that may change script partway through.
     *
     * Each run goes to the engine for its own script and the pieces are joined back together, so
     * "불꽃처럼 燃えろ" comes back as Korean romanization followed by Japanese rather than half of one.
     * A Latin run passes through untouched — there is nothing to convert, and dropping it would lose
     * the English word in the middle of the line.
     */
    fun romanizeMixed(
        text: String,
        hanScript: Script,
        stripDiacritics: Boolean = false,
        hokkien: HokkienSpelling? = null,
    ): String? {
        val runs = scriptRuns(text, hanScript)
        if (runs.isEmpty()) return null
        if (runs.size == 1) return romanizeText(text, runs.first().script, stripDiacritics, hokkien)

        var converted = false
        val out = StringBuilder()
        for (run in runs) {
            val piece = romanizeText(run.text, run.script, stripDiacritics, hokkien)
            if (piece != null) converted = true
            val text = piece ?: run.text
            if (text.isEmpty()) continue
            // A gap between two runs, unless one of them already brought its own: `moeroBULLETPROOF`
            // is not a reading of anything.
            if (out.isNotEmpty() && !out.last().isWhitespace() && !text.first().isWhitespace()) {
                out.append(' ')
            }
            out.append(text)
        }
        if (!converted) return null
        return out.toString().replace(Regex("\\s{2,}"), " ").trim().takeIf { it.isNotEmpty() }
    }

    fun romanizeText(
        text: String,
        script: Script,
        stripDiacritics: Boolean = false,
        /** Read Chinese as Taiwanese Hokkien, spelled this way, rather than as Mandarin. */
        hokkien: HokkienSpelling? = null,
    ): String? {
        if (text.isBlank()) return null
        // A radical that depicts an ideograph is not that ideograph, and nothing has a reading for
        // one. Canonical for the engines only; the "did anything change" test below still compares
        // against what arrived, so a character that genuinely has no reading still reports none.
        val source = HanCanonical.of(text)
        val converted = when (script) {
            Script.JAPANESE -> {
                val tokenizer = this.tokenizer ?: return null
                runCatching {
                    tokenizer.tokenize(source).joinToString("") { token ->
                        val romaji = token.romaji()
                        if (romaji.isEmpty()) "" else "$romaji "
                    }.trim()
                }.getOrNull()
            }

            Script.CHINESE -> if (hokkien != null) {
                HokkienWords.romanize(source, poj = hokkien == HokkienSpelling.POJ)
            } else {
                chinesePinyin(source)
            }
            Script.KOREAN -> transliterate("Hangul-Latin", source)
            Script.CYRILLIC -> transliterate("Cyrillic-Latin", source)
            Script.GREEK -> transliterate("Greek-Latin", source)
            Script.LATIN, Script.OTHER -> null
        } ?: return null

        val cleaned = converted.replace(Regex("\\s{2,}"), " ").trim()
        if (cleaned.isEmpty() || cleaned == text) return null
        return if (stripDiacritics) {
            if (hokkien != null && script == Script.CHINESE) {
                HokkienWords.stripTones(cleaned)
            } else {
                diacriticStripper?.transliterate(cleaned) ?: cleaned
            }
        } else {
            cleaned
        }
    }

    /**
     * Pinyin for a stretch of Chinese, by word where the word matters.
     *
     * [PinyinWords] holds the 31,710 words ICU reads wrongly; everything else comes from ICU, which
     * is right about the rest. So a line is a mix, and has to be assembled a character at a time —
     * there is no single call that knows both halves.
     */
    private fun chinesePinyin(text: String): String? {
        val byWord = PinyinWords.readings(text) ?: return transliterate("Han-Latin", text)

        // By code point, not by char. An emoji or an Extension-B ideograph is a surrogate pair,
        // and stepping through UTF-16 units would read each half separately and push a separator
        // between them, turning one character into two broken ones.
        val out = StringBuilder(text.length * 4)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            val raw = text.substring(index, index + width)
            val reading = byWord[index]
                ?: (if (width == 1) singleCharPinyin(text[index]) else null)
                ?: raw
            if (out.isNotEmpty() && !out.last().isWhitespace() && !reading.first().isWhitespace()) {
                out.append(' ')
            }
            out.append(reading)
            index += width
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    /**
     * One character through ICU, remembered.
     *
     * A line only reaches here for the characters no word covered, but the same few hundred
     * characters come back on every line of every song, and a transliterator call each time is a
     * waste of the only budget that matters while the words are moving.
     */
    private fun singleCharPinyin(char: Char): String? = singleChars.getOrPut(char) {
        transliterate("Han-Latin", char.toString())?.trim().orEmpty()
    }.takeIf { it.isNotEmpty() }

    private val singleChars = HashMap<Char, String>(512)

    /**
     * The reading for one syllable's characters, taken from the line's word-level readings.
     *
     * Null when no word covered any of them, which leaves the syllable to the ordinary path — the
     * table only holds what ICU gets wrong, so "not covered" means "ICU was already right".
     */
    private fun stripDiacritics(text: String): String =
        diacriticStripper?.let { runCatching { it.transliterate(text) }.getOrNull() } ?: text

    /**
     * How long a syllable has to last to count as held: twice the song's typical syllable, and 700 ms
     * at the least. Measured on word-timed songs, a 了 sung in passing lasts 0.2 to 1 times the
     * typical syllable, and one held out 2.4 to 3.7 times.
     */
    private fun heldFor(document: LyricsDocument): Int {
        val lengths = document.lines.flatMap { line ->
            line.syllables.filter { it.text.isNotBlank() && !it.endInferred }.map { it.endMs - it.startMs }
        }.filter { it > 0 }.sorted()
        if (lengths.size < 20) return Int.MAX_VALUE
        return maxOf(2 * lengths[lengths.size / 2], 700)
    }

    /**
     * 了 held out is sung *liǎo*, not the particle's *le*: a held note needs a vowel to hold, and
     * singers give it one. Nothing in the text says so, only the timing, so only a syllable that
     * ends in 了 — punctuation aside — and lasts at least [held] changes, and only where it would
     * otherwise read *le*. A syllable whose end the source never gave is left alone: its length
     * includes whatever pause follows it.
     */
    private fun sungLiao(syllable: Syllable, romanized: String, held: Int, stripDiacritics: Boolean): String {
        if (syllable.endInferred || syllable.endMs - syllable.startMs < held) return romanized
        if (!syllable.text.trimEnd { !it.isLetterOrDigit() }.endsWith('了')) return romanized
        val spoken = romanized.trimEnd { !it.isLetter() }
        if (spoken.substringAfterLast(' ') != "le") return romanized
        return spoken.dropLast(2) + (if (stripDiacritics) "liao" else "liǎo") + romanized.substring(spoken.length)
    }

    private fun spanReading(
        byWord: Array<String?>,
        lineText: String,
        start: Int,
        length: Int,
    ): String? {
        if (length <= 0 || start + length > lineText.length) return null
        var covered = false
        val out = StringBuilder(length * 4)
        var index = start
        while (index < start + length) {
            val width = Character.charCount(lineText.codePointAt(index))
            if (byWord[index] != null) covered = true
            // A supplementary character has no per-character reading to fall back on, so the whole
            // syllable goes to the ordinary path rather than being read half a codepoint at a time.
            val reading = byWord[index]
                ?: (if (width == 1) singleCharPinyin(lineText[index]) else null)
                ?: return null
            if (out.isNotEmpty()) out.append(' ')
            out.append(reading)
            index += width
        }
        if (!covered) return null
        return out.toString().takeIf { it.isNotEmpty() }
    }

    private fun transliterate(id: String, text: String): String? {
        val instance = transliterators.getOrPut(id) {
            runCatching { Transliterator.getInstance(id) }.getOrNull()
        } ?: return null
        return runCatching { instance.transliterate(text) }.getOrNull()
    }

    /**
     * Build the Kuromoji dictionary once. It costs a second or two and a few tens of
     * megabytes of heap, so it is only paid for on the first Japanese track of the
     * session and never on the main thread.
     */
    suspend fun prepareTokenizer() {
        if (tokenizer != null) return
        tokenizerLock.withLock {
            if (tokenizer != null) return
            tokenizer = withContext(Dispatchers.IO) {
                runCatching { Tokenizer() }.getOrNull()
            }
        }
    }
}

/**
 * Whether a romanization still has work left in it.
 *
 * Blank means nothing was produced; anything still in a non-Latin script means an engine was handed
 * text it did not understand and gave it back unchanged, which is how a Japanese dictionary answers a
 * question about Hangul.
 */
internal fun needsAnotherPass(romanized: String?): Boolean =
    romanized.isNullOrBlank() || containsNonLatinScript(romanized)
