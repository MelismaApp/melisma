package com.melisma.app.car

import com.melisma.app.lyrics.LyricsState
import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
import com.melisma.app.settings.Settings
import com.melisma.app.settings.TranslationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a car screen is allowed to say.
 *
 * Worth testing away from a car because none of it can be seen from one: a template only reports
 * what it was given, and the interesting failures here are all about *which* words that is. Showing
 * the wrong line is a driver looking twice; showing a whole verse is a driver reading. Both are the
 * sort of thing you find by plugging a phone into a car and squinting, which is not a test.
 */
class CarGlanceTest {

    private fun line(
        text: String,
        startMs: Int,
        endMs: Int,
        role: LineRole = LineRole.LEAD,
        romanized: String? = null,
        translated: String? = null,
        syllables: List<Syllable> = emptyList(),
    ) = LyricLine(
        role = role,
        startMs = startMs,
        endMs = endMs,
        text = text,
        syllables = syllables,
        romanized = romanized,
        translated = translated,
    )

    private fun document(vararg lines: LyricLine, kind: LyricsKind = LyricsKind.SYLLABLE) =
        LyricsDocument(kind = kind, lines = lines.toList(), providerName = "Test", providerId = "test")

    /**
     * A song shaped the way a parser actually produces one.
     *
     * The interludes matter: every gap of three seconds or more arrives as an explicit
     * [LineRole.INTERLUDE] line, because that is what the renderer draws its three dots from. A
     * hand-built document without them is not a document any provider would hand over, and a test
     * using one proves something about a shape that cannot occur.
     */
    private val song = document(
        line("", 0, 4_000, role = LineRole.INTERLUDE),
        line("Is this the real life", 4_000, 7_000),
        line("Is this just fantasy", 7_000, 10_000),
        line("", 10_000, 20_000, role = LineRole.INTERLUDE),
        line("Caught in a landslide", 20_000, 23_000),
        line("No escape from reality", 23_500, 26_000),
    )

    private fun glance(
        positionMs: Long,
        document: LyricsDocument = song,
        settings: Settings = Settings(),
    ): CarGlance = CarGlance.of(
        state = LyricsState.Loaded(
            document = document,
            romanizationAvailable = false,
            translationAvailable = false,
            translationPossible = false,
        ),
        hasTrack = true,
        permissionGranted = true,
        positionMs = positionMs,
        settings = settings,
    )

    private fun now(positionMs: Long, settings: Settings = Settings()) =
        glance(positionMs, settings = settings) as CarGlance.Now

    @Test
    fun `the line being sung, and the one after it`() {
        val glance = now(5_000)
        assertEquals("Is this the real life", glance.line)
        assertEquals("Is this just fantasy", glance.next)
    }

    @Test
    fun `two lines and no more, whatever the song does`() {
        // The whole design in one assertion: there is nowhere in this type to put a third line, and
        // that is the point rather than an omission. A car screen that can grow to a verse will.
        val fields = CarGlance.Now::class.java.declaredFields.map { it.name }
        assertTrue("line" in fields && "next" in fields)
        assertTrue("no third line may be added without deciding to", fields.none { it == "after" })
    }

    @Test
    fun `an instrumental says so instead of leaving the last line up`() {
        // A ten-second break, which the parser marks as an interlude. Holding "Is this just fantasy"
        // there would have the driver reading a line that stopped being true nine seconds ago.
        val glance = now(15_000)
        assertTrue(glance.instrumental)
        assertNull(glance.line)
        assertEquals("Caught in a landslide", glance.next)
    }

    @Test
    fun `a breath between two lines is not an instrumental`() {
        // The other half of the same rule. A gap under three seconds gets no interlude line, so the
        // words just sung stay up — blanking the screen for half a second between lines would be a
        // flicker in the corner of somebody's eye at 70mph.
        val glance = now(23_200)
        assertEquals("Caught in a landslide", glance.line)
        assertEquals("No escape from reality", glance.next)
    }

    @Test
    fun `the intro is instrumental, not a blank line`() {
        val glance = now(1_000)
        assertTrue(glance.instrumental)
        assertNull(glance.line)
        assertEquals("Is this the real life", glance.next)
    }

    @Test
    fun `the last line stays up with nothing after it`() {
        val glance = now(60_000)
        assertEquals("No escape from reality", glance.line)
        assertNull(glance.next)
    }

    @Test
    fun `a background vocal never becomes the line`() {
        // Backing vocals sit inside their lead line's window. Letting one win would flip the row
        // between a line and its own backing track, twice a second, in a moving car.
        val withBacking = document(
            line("Lead line", 0, 5_000),
            line("ooh", 1_000, 2_000, role = LineRole.BACKGROUND),
            line("Next line", 5_000, 8_000),
        )
        val glance = glance(1_500, withBacking) as CarGlance.Now
        assertEquals("Lead line", glance.line)
        assertEquals("Next line", glance.next)
    }

    @Test
    fun `a paused song still knows where it is`() {
        // Nothing in the answer depends on playback state: a paused playhead is a position like any
        // other, and the screen must not blank because the music stopped.
        assertEquals("Is this the real life", now(5_000).line)
    }

    @Test
    fun `romanization is honoured, because whoever asked for it cannot read the original`() {
        val japanese = document(
            line("不完全な僕を", 0, 3_000, romanized = "fu ka n ze n na bo ku wo"),
            line("夢ならば", 3_000, 6_000, romanized = "yu me na ra ba"),
        )
        val off = glance(1_000, japanese, Settings(showRomanization = false)) as CarGlance.Now
        assertEquals("不完全な僕を", off.line)

        val on = glance(1_000, japanese, Settings(showRomanization = true)) as CarGlance.Now
        assertEquals("fu ka n ze n na bo ku wo", on.line)
        assertEquals("yu me na ra ba", on.next)
    }

    @Test
    fun `a syllable-romanized line is composed rather than left as kanji`() {
        // NetEase and the community database romanize per syllable and leave the line-level field
        // empty. Falling back to the original there would show kanji to somebody who asked for
        // romaji — the one reader guaranteed not to be able to use it.
        val perSyllable = document(
            line(
                "君の声が",
                0,
                3_000,
                syllables = listOf(
                    Syllable("君", 0, 700, romanized = "kimi", romanizedStartsWord = true),
                    Syllable("の", 700, 1_200, romanized = "no", romanizedStartsWord = true),
                    Syllable("声", 1_200, 2_000, romanized = "koe", romanizedStartsWord = true),
                    Syllable("が", 2_000, 3_000, romanized = "ga", romanizedStartsWord = true),
                ),
            ),
        )
        val glance = glance(1_000, perSyllable, Settings(showRomanization = true)) as CarGlance.Now
        assertEquals("kimi no koe ga", glance.line)
    }

    @Test
    fun `a syllable that continues a word does not gain a space`() {
        val together = document(
            line(
                "together",
                0,
                3_000,
                syllables = listOf(
                    Syllable("to", 0, 1_000, romanized = "to"),
                    Syllable("geth", 1_000, 2_000, romanized = "geth", partOfWord = true),
                    Syllable("er", 2_000, 3_000, romanized = "er", partOfWord = true),
                ),
            ),
        )
        val glance = glance(500, together, Settings(showRomanization = true)) as CarGlance.Now
        assertEquals("together", glance.line)
    }

    @Test
    fun `a translation rides along only when it is switched on`() {
        val translated = document(
            line("不完全な僕を", 0, 3_000, translated = "My incomplete self"),
        )
        val off = glance(1_000, translated, Settings(translationSource = TranslationSource.OFF))
        assertNull((off as CarGlance.Now).beneath)

        val on = glance(1_000, translated, Settings(translationSource = TranslationSource.PROVIDER))
        assertEquals("My incomplete self", (on as CarGlance.Now).beneath)
    }

    @Test
    fun `the sync offset moves the car screen with the phone`() {
        // The offset is applied by the caller, which is the contract this documents: at 3.9s the
        // first line has not started, and a 200ms nudge is what decides that.
        assertNull(now(3_900).line)
        assertEquals("Is this the real life", now(3_900 + 200).line)
    }

    @Test
    fun `untimed lyrics are refused rather than shown as a wall`() {
        val static = document(
            line("Every word at once", 0, 0),
            line("and no way to follow it", 0, 0),
            kind = LyricsKind.STATIC,
        )
        assertEquals(CarGlance.Untimed, glance(1_000, static))
    }

    @Test
    fun `states that are not words each say their own thing`() {
        fun of(state: LyricsState, hasTrack: Boolean = true, granted: Boolean = true) =
            CarGlance.of(state, hasTrack, granted, 0L, Settings())

        assertEquals(CarGlance.Looking, of(LyricsState.Loading))
        assertEquals(CarGlance.Looking, of(LyricsState.Idle))
        assertEquals(CarGlance.None, of(LyricsState.NotFound))
        assertEquals(CarGlance.Offline, of(LyricsState.Offline))
        assertEquals(CarGlance.Failed("boom"), of(LyricsState.Failed("boom")))
        assertEquals(CarGlance.Silent, of(LyricsState.Loading, hasTrack = false))
        // The permission cannot be granted from a car seat, so saying so beats a blank screen.
        assertEquals(CarGlance.NoPermission, of(LyricsState.Loading, granted = false))
        // And it outranks everything: without it nothing else can be true yet.
        assertEquals(CarGlance.NoPermission, of(LyricsState.NotFound, hasTrack = false, granted = false))
    }
}
