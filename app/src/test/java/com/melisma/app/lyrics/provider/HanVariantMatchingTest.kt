package com.melisma.app.lyrics.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The same Chinese title in Traditional and Simplified is the same title.
 *
 * Reported from the cache-server side, which hit it comparing sources to each other. The app hits it
 * one step earlier, deciding whether a search result *is* the track playing: the two scripts can
 * share no characters at all, so the title similarity goes to 0 and a correct match is discarded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HanVariantMatchingTest {

    private fun request(title: String, artist: String, durationMs: Long) = LyricsRequest(
        title = title,
        artist = artist,
        album = "",
        durationMs = durationMs,
    )

    @Test
    fun `a title in the other script is still a match`() {
        // 獨角獸 and 独角兽 are the same word; only 角 is written the same way, so two thirds of the
        // characters have nothing in common.
        assertTrue(naiveSimilarity("獨角獸", "独角兽") < 0.4f)
        assertTrue(
            "Traditional and Simplified should fold together",
            Matching.similarity("獨角獸", "独角兽") > 0.99f,
        )
    }

    @Test
    fun `and the candidate is no longer thrown away`() {
        // The shape that actually failed. A variant title alone survives, because a perfect artist and
        // duration put a floor of 0.5 under the score and the title only has to reach 0.24. It is when
        // the *artist* is in the other script too — the ordinary case for a Traditional catalogue
        // answering a Simplified player — and the player reports no duration, that it falls through:
        //
        //   title  獨角獸/独角兽   0.33 x 0.5 = 0.167
        //   artist 張惠妹/张惠妹   0.67 x 0.3 = 0.200
        //   duration unknown     0.50 x 0.2 = 0.100
        //                                     -----
        //                                     0.467, against a threshold of 0.62
        assertTrue(naiveSimilarity("張惠妹", "张惠妹") < 0.7f)

        val playing = request("独角兽", "张惠妹", durationMs = 0)
        val score = Matching.score(playing, "獨角獸", "張惠妹", candidateDurationMs = 0)

        assertTrue(
            "scored $score, needs >= ${Matching.MATCH_THRESHOLD}",
            score >= Matching.MATCH_THRESHOLD,
        )
    }

    @Test
    fun `knowing the duration exactly did not save it either`() {
        // Raised from the cache-server side, which measured the same shape and found this row the
        // interesting one. An exact duration match does *not* rescue a variant title once the artist
        // is shifted too:
        //
        //   title  獨角獸/独角兽   0.33 x 0.5 = 0.167
        //   artist 吳青峰/吴青峰   0.67 x 0.3 = 0.200
        //   duration exact       1.00 x 0.2 = 0.200
        //                                     -----
        //                                     0.567, still under 0.62
        //
        // So the lyrics were being discarded even for tracks whose length was known to the
        // millisecond, which is a wider reach than the no-duration case above.
        val playing = request("独角兽", "吴青峰", 398_720)
        val score = Matching.score(playing, "獨角獸", "吳青峰", 398_720)

        assertTrue(
            "scored $score, needs >= ${Matching.MATCH_THRESHOLD}",
            score >= Matching.MATCH_THRESHOLD,
        )
    }

    @Test
    fun `a genuinely different song is still refused`() {
        // The folding must not turn the matcher into a pushover: same artist, same duration, a title
        // that is simply another song.
        val playing = request("独角兽", "告五人", 398_720)
        val score = Matching.score(playing, "愛人錯過", "告五人", 398_720)

        assertTrue("scored $score, should be under the threshold", score < Matching.MATCH_THRESHOLD)
    }

    @Test
    fun `titles in other scripts are untouched`() {
        assertEquals(1f, Matching.similarity("アイドル", "アイドル"), 0.001f)
        assertEquals(1f, Matching.similarity("Boompala", "boompala"), 0.001f)
        assertTrue(Matching.similarity("좋은 날", "Good Day") < 0.5f)
    }

    /** Levenshtein with no folding, to show what the matcher used to see. */
    private fun naiveSimilarity(a: String, b: String): Float {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        val distance = previous[b.length]
        return (1f - distance.toFloat() / maxOf(a.length, b.length)).coerceIn(0f, 1f)
    }
}
