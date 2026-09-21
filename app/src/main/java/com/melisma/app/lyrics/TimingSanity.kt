package com.melisma.app.lyrics

import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind

/**
 * Whether a document's timings agree with the song they claim to describe.
 *
 * Reported as "Musixmatch timing always seems worse — the Apple one should beat it at least".
 * Ranking alone could not fix that, because both answers called themselves word-synced and the
 * tier is the one thing a preference may not overrule. The question is whether the claim is true.
 *
 * Both checks here were calibrated against the cache server's archive of what the providers
 * actually sent: 384 word-timed documents over 400 tracks, four providers, with the same track
 * from two or three of them in most cases — which is what makes the numbers mean anything. A
 * provider is only measurably wrong about a song when another provider is measurably right about
 * the same song.
 *
 * Two things the archive showed, and one it disproved:
 *
 *  - **A fragment per line, called word-sync.** For Chinese and Japanese, Musixmatch richsync
 *    routinely returns one fragment holding an entire line: 咏春 came back as 45 "syllables" of
 *    ten characters each where NetEase had 467 of one character, and Lemon as 47 against Apple's
 *    273 and AMLL's 520. Nothing is out of order and nothing is missing — every line simply
 *    highlights in one block, which is line-sync wearing a word-sync hat. 18 of 135 Musixmatch
 *    documents were more than half made of such lines, against 0 of 80 from NetEase, 0 of 12
 *    from AMLL, and 1 of 157 from Apple. See [hasWordTimings].
 *
 *  - **Timings that run past the end of the track.** NetEase's Irony ran to 5:48 on a 2:24
 *    recording, with 74% of its syllables starting after the song had finished; 紅 47%,
 *    Tame Impala's Borderline 21%, AMLL's CRAZY 13%. These are a different recording — a live
 *    take, an extended mix — and no amount of it is usable against what is playing. The next
 *    document down was at 2.5%, which is a remaster a few seconds longer and perfectly fine, so
 *    the threshold sits in a real gap rather than on a guess. See [timingsOutrunTheTrack].
 *
 *  - **Syllables out of order, which is not worth acting on.** It was the first thing to look
 *    for and the archive says leave it alone: where it happens at all it is 1 to 8 syllables in
 *    a document of 400, at most 1.7%, and the documents containing it are otherwise good. There
 *    is no threshold that catches a mangled document without also throwing away a fine one, so
 *    there is no check for it — the parsers clamp a backwards syllable and the sweep carries on.
 */
object TimingSanity {

    /**
     * A single fragment this many characters long is a line, not a word.
     *
     * Short lines are exempt because a one-word line really is one syllable: "Oh", "Yeah" and
     * "Hey" are sung in one block and timed in one fragment by every source. Six characters is
     * comfortably above those and far below a sung phrase in any script — the coarse documents in
     * the archive averaged five to eleven characters per fragment, and the genuine ones one to
     * three for CJK and under four for English.
     */
    const val WHOLE_LINE_CHARS = 6

    /**
     * How far past the track's end a timing may sit before it counts as outside the song.
     *
     * A player's reported duration and a provider's idea of the same recording disagree by a
     * second or two routinely — different masters, and the trailing silence counted or not.
     */
    const val PAST_END_TOLERANCE_MS = 2_000

    /**
     * The share of syllables that may start after the track has ended.
     *
     * The archive splits cleanly at this line: the four documents describing a different
     * recording sat at 13%, 21%, 46% and 74%, and everything else at 2.5% or below.
     */
    const val MAX_PAST_END_SHARE = 0.10f

    /**
     * Whether this line is word-timed in the sense that matters — that the highlight moves
     * *through* it rather than landing on all of it at once.
     *
     * One fragment covering a whole line is the case that used to pass: `kind` is set from the
     * presence of any syllable at all, so a document of nothing but such lines claimed the
     * syllable tier and beat a genuine word-timed answer that happened to be from a
     * lower-ranked source.
     */
    fun hasWordTimings(line: LyricLine): Boolean = when {
        line.syllables.isEmpty() -> false
        line.syllables.size >= 2 -> true
        // One fragment, so it is word-timed only if there was one word to time.
        else -> line.text.trim().length < WHOLE_LINE_CHARS
    }

    /**
     * Whether the document has any timing information at all, as opposed to a timestamp it repeats.
     *
     * Apple serves unsynced lyrics as the same TTML it uses for synced ones, with no `itunes:timing`
     * and no `begin` anywhere — so every line parses to 0ms and the document calls itself
     * line-synced. 14 of the 339 Apple documents in the archive were this, and the effect is the one
     * that reads as broken timing rather than missing timing: a full transcription that claims to
     * follow the song, outranks a source that honestly admits it has no timings, and then never
     * advances off the first line.
     *
     * A single line is exempt: it cannot be out of step with itself, and one timestamp is all it has.
     */
    fun hasUsableTimings(document: LyricsDocument): Boolean {
        val vocal = document.vocalLines
        if (vocal.size < 2) return true

        val seen = HashSet<Int>(4)
        for (line in vocal) {
            seen += line.startMs
            for (syllable in line.syllables) seen += syllable.startMs
            if (seen.size >= 2) return true
        }
        return false
    }

    /**
     * Whether enough of the timings fall outside the track to say this describes another
     * recording. Unanswerable without a duration, so a track whose player did not report one is
     * given the benefit of the doubt.
     */
    fun timingsOutrunTheTrack(document: LyricsDocument, trackDurationMs: Long): Boolean {
        if (trackDurationMs <= 0) return false
        val syllables = document.vocalLines.flatMap { it.syllables }
        val timings = if (syllables.isNotEmpty()) {
            syllables.map { it.startMs }
        } else {
            document.vocalLines.map { it.startMs }
        }
        if (timings.isEmpty()) return false

        val limit = trackDurationMs + PAST_END_TOLERANCE_MS
        val past = timings.count { it > limit }
        return past.toFloat() / timings.size > MAX_PAST_END_SHARE
    }

    /**
     * The document, with its `kind` corrected to what its timings support.
     *
     * Applied once, where the answers arrive and the track's duration is known, so the honest
     * kind is what gets ranked, cached and shown. A document is only ever moved *down*: the
     * checks can prove a claim false and never prove one true.
     *
     * Dropping one tier rather than to [LyricsKind.STATIC] is deliberate. When another source
     * answered, one tier is all it takes for the genuine one to win, which is the whole point.
     * When nothing else answered, these are still the only lyrics there are, and lines that
     * scroll slightly wrong beat no lyrics at all.
     */
    fun honestKind(document: LyricsDocument, trackDurationMs: Long): LyricsDocument =
        if (timingsOutrunTheTrack(document, trackDurationMs)) {
            document.copy(kind = document.kind.oneTierDown())
        } else {
            document
        }

    private fun LyricsKind.oneTierDown(): LyricsKind = when (this) {
        LyricsKind.SYLLABLE -> LyricsKind.LINE
        LyricsKind.LINE -> LyricsKind.STATIC
        LyricsKind.STATIC -> LyricsKind.STATIC
    }
}
