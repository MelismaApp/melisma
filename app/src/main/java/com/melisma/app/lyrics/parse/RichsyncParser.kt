package com.melisma.app.lyrics.parse

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.Syllable
import com.melisma.app.lyrics.model.inferEndTimes
import com.melisma.app.lyrics.model.withInterludes
import com.melisma.app.util.isRtlText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for Musixmatch's *richsync* — the one format of theirs that carries per-word timings:
 *
 * ```
 * [{"ts":1.2,"te":3.4,"x":"Hello world","l":[{"c":"Hello","o":0.0},{"c":" ","o":0.5}]}]
 * ```
 *
 * `ts`/`te` bound the line, `o` is each fragment's offset from `ts`. Whitespace fragments are
 * separators, not syllables.
 *
 * It lives out here, next to the parsers for the other three formats, rather than inside the
 * provider that fetches it. Being private to a class that needs a token and a network was the
 * reason the one word-timed format in the app with no direct tests was this one — and it is the
 * format that turned out to ship timings that contradict themselves. See [TimingSanity].
 */
object RichsyncParser {

    fun parse(body: String, trackDurationMs: Long): List<LyricLine>? {
        val array = runCatching { Json.parseToJsonElement(body).jsonArray }.getOrNull()
            ?: return null
        if (array.isEmpty()) return null

        val lines = ArrayList<LyricLine>(array.size)
        for (element in array) {
            val entry = runCatching { element.jsonObject }.getOrNull() ?: continue
            val ts = entry["ts"]?.jsonPrimitive?.doubleOrNull ?: continue
            val te = entry["te"]?.jsonPrimitive?.doubleOrNull ?: ts
            val fullText = entry["x"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val fragments = runCatching { entry["l"]?.jsonArray }.getOrNull()

            val lineStart = (ts * 1000).toInt()
            val lineEnd = (te * 1000).toInt()

            if (fragments == null || fragments.isEmpty()) {
                if (fullText.isEmpty()) continue
                lines += LyricLine(
                    role = LineRole.LEAD,
                    startMs = lineStart,
                    endMs = lineEnd,
                    text = fullText,
                    rtl = fullText.isRtlText(),
                )
                continue
            }

            // Collect (text, startMs) first so each fragment can be closed at the
            // next one's start.
            val raw = ArrayList<Pair<String, Int>>(fragments.size)
            for (fragment in fragments) {
                val obj = runCatching { fragment.jsonObject }.getOrNull() ?: continue
                val chars = obj["c"]?.jsonPrimitive?.contentOrNull ?: continue
                val offset = obj["o"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                raw += chars to (ts * 1000 + offset * 1000).toInt()
            }

            val syllables = ArrayList<Syllable>(raw.size)
            val text = StringBuilder()
            var previousEndedWithSpace = true
            for ((index, pair) in raw.withIndex()) {
                val (chars, start) = pair
                val trimmed = chars.trim()
                if (trimmed.isEmpty()) {
                    previousEndedWithSpace = true
                    continue
                }
                val end = raw.getOrNull(index + 1)?.second ?: lineEnd
                val partOfWord = !previousEndedWithSpace && !chars.startsWith(" ")
                syllables += Syllable(
                    text = trimmed,
                    startMs = start,
                    endMs = end.coerceAtLeast(start),
                    partOfWord = partOfWord,
                )
                if (!partOfWord && text.isNotEmpty()) text.append(' ')
                text.append(trimmed)
                previousEndedWithSpace = chars.endsWith(" ")
            }

            if (syllables.isEmpty()) continue
            lines += LyricLine(
                role = LineRole.LEAD,
                startMs = minOf(lineStart, syllables.first().startMs),
                endMs = maxOf(lineEnd, syllables.last().endMs),
                text = text.toString().ifEmpty { fullText },
                syllables = syllables,
                rtl = text.toString().isRtlText(),
            )
        }

        if (lines.isEmpty()) return null
        return lines.sortedBy { it.startMs }.inferEndTimes(trackDurationMs).withInterludes()
    }
}
