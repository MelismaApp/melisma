package com.melisma.app.lyrics

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Whether ranking a source actually does anything.
 *
 * Reported as "Boompala uses Musixmatch even though it is at the bottom of the list — is the priority
 * reversed?" It was not reversed. It was barely consulted: quality was one number combining the timing
 * tier with completeness and bonus points, so the user's order only broke an *exact* tie between two
 * totals, and the fourteen points below the tier almost always broke it first.
 *
 * And the way it broke it was circular. The fullest answer sets the yardstick every other answer is
 * measured against, so a source returning mangled, duplicated lines inflated the line count and then
 * won the completeness score it had just defined. Ranking it last changed nothing at all.
 */
class SourcePriorityTest {

    private fun document(
        provider: String,
        kind: LyricsKind,
        lines: Int,
        timed: Boolean = kind == LyricsKind.SYLLABLE,
        romanized: Boolean = false,
        translated: Boolean = false,
    ) = LyricsDocument(
        kind = kind,
        lines = (0 until lines).map { index ->
            LyricLine(
                role = LineRole.LEAD,
                startMs = index * 2_000,
                endMs = index * 2_000 + 1_500,
                text = "line $index",
                // Per word, because one syllable spanning the whole line is line timing wearing a
                // word-timed hat — see `TimingSanity.hasWordTimings`.
                syllables = if (timed) {
                    listOf(
                        Syllable("line", index * 2_000, index * 2_000 + 750),
                        Syllable("$index", index * 2_000 + 750, index * 2_000 + 1_500),
                    )
                } else {
                    emptyList()
                },
            )
        },
        providerName = provider,
        providerId = provider,
        hasRomanization = romanized,
        hasTranslation = translated,
    )

    /** The default order, most preferred first. */
    private val order = listOf(
        "local", "cacheserver", "applemusic", "spotify", "amll", "netease", "musixmatch", "lrclib",
    )

    private fun pick(vararg answers: Pair<String, LyricsDocument>): String? {
        val best = answers.maxOfOrNull { (_, document) -> lineCount(document) } ?: 0
        val winner = pickBest(answers.toList(), order, best)
        return winner?.providerId
    }

    @Test
    fun `the source ranked higher wins between two equally timed answers`() {
        // The report. Both word-timed and complete; AMLL is fifth in the order and Musixmatch seventh,
        // so AMLL takes it — and used to lose to a single extra line.
        assertEquals(
            "amll",
            pick(
                "musixmatch" to document("musixmatch", LyricsKind.SYLLABLE, lines = 21),
                "amll" to document("amll", LyricsKind.SYLLABLE, lines = 20),
            ),
        )
    }

    @Test
    fun `extra lines no longer buy a lower-ranked source the win`() {
        // The circular part: the answer with the most lines defines the completeness everyone else is
        // scored against, so padded or duplicated lyrics used to win by a yardstick of their own making.
        assertEquals(
            "netease",
            pick(
                "musixmatch" to document("musixmatch", LyricsKind.SYLLABLE, lines = 40),
                "netease" to document("netease", LyricsKind.SYLLABLE, lines = 25),
            ),
        )
    }

    @Test
    fun `nor do its own romanization and translation`() {
        assertEquals(
            "amll",
            pick(
                "musixmatch" to document(
                    "musixmatch",
                    LyricsKind.SYLLABLE,
                    lines = 20,
                    romanized = true,
                    translated = true,
                ),
                "amll" to document("amll", LyricsKind.SYLLABLE, lines = 20),
            ),
        )
    }

    @Test
    fun `word timings still beat a preference, because that is the difference that matters`() {
        // The property the ranking may not overrule: Apple Music is third and NetEase sixth, but a
        // line-timed answer is a teleprompter and a word-timed one is karaoke. No amount of preference
        // makes the first into the second.
        assertEquals(
            "netease",
            pick(
                "applemusic" to document("applemusic", LyricsKind.LINE, lines = 20),
                "netease" to document("netease", LyricsKind.SYLLABLE, lines = 20),
            ),
        )
    }

    @Test
    fun `a fragment with perfect timings still loses to a whole transcription`() {
        // The fragment guard lives in the tier, where a preference cannot reach it: four word-timed
        // lines out of forty is a truncated transcription, whatever its timings are like.
        assertEquals(
            "lrclib",
            pick(
                "amll" to document("amll", LyricsKind.SYLLABLE, lines = 4),
                "lrclib" to document("lrclib", LyricsKind.LINE, lines = 40),
            ),
        )
    }

    @Test
    fun `completeness still decides when nothing else can`() {
        // It has not stopped mattering — it is just last now, which is where it belongs. Two answers
        // from sources the user never ranked, in the same tier, and the fuller one wins.
        assertEquals(
            "unknown-b",
            pick(
                "unknown-a" to document("unknown-a", LyricsKind.LINE, lines = 10),
                "unknown-b" to document("unknown-b", LyricsKind.LINE, lines = 30),
            ),
        )
    }

    @Test
    fun `reordering the list changes the answer`() {
        // The whole point of the setting, and the thing that was not true before: moving a source up
        // has to change which lyrics appear.
        val answers = listOf(
            "musixmatch" to document("musixmatch", LyricsKind.SYLLABLE, lines = 20),
            "amll" to document("amll", LyricsKind.SYLLABLE, lines = 20),
        )
        val best = answers.maxOf { (_, document) -> lineCount(document) }

        assertEquals("amll", pickBest(answers, order, best)?.providerId)
        val musixmatchFirst = listOf("musixmatch") + order.filterNot { it == "musixmatch" }
        assertEquals("musixmatch", pickBest(answers, musixmatchFirst, best)?.providerId)
    }

    /**
     * The comparison `upgradeAfter` makes when a cached track hears back from a source that had never
     * been asked. It used to use `qualityScore`, which cannot see the user's order at all: enabling a
     * source and moving it to the top got it asked once, its equally good answer lost by a hair of
     * completeness, and it was then recorded as asked — so it was never offered again for the life of
     * the cache entry and the reorder did nothing. Ties must still keep the cached answer, because
     * swapping the words on screen for no visible gain is worse than leaving them.
     */
    @Test
    fun `an equally good answer from a higher-ranked source replaces the cached one`() {
        val cached = document("musixmatch", LyricsKind.SYLLABLE, lines = 20)
        val arrived = document("amll", LyricsKind.SYLLABLE, lines = 20)
        // The user has since put AMLL first.
        val reordered = listOf("amll") + order.filterNot { it == "amll" }

        assertEquals(
            "amll",
            pickBest(
                listOf("musixmatch" to cached, "amll" to arrived),
                reordered,
                bestLines = 20,
            )?.providerId,
        )
    }

    @Test
    fun `but an equal answer from an equally ranked source does not`() {
        // Same source answering again with nothing new. Listed first, so the tie keeps it and the
        // cache is not rewritten.
        val cached = document("netease", LyricsKind.SYLLABLE, lines = 20)
        val arrived = document("netease", LyricsKind.SYLLABLE, lines = 20)

        assertSame(
            cached,
            pickBest(listOf("netease" to cached, "netease" to arrived), order, bestLines = 20),
        )
    }

    @Test
    fun `nothing at all is nothing`() {
        assertEquals(null, pickBest(emptyList(), order, 0)?.providerId)
    }
}
