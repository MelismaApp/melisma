package com.melisma.app.lyrics

import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
import com.melisma.app.lyrics.parse.RichsyncParser
import com.melisma.app.lyrics.parse.TtmlParser
import com.melisma.app.lyrics.parse.YrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether a document's timings agree with the song, calibrated against the cache server's archive
 * of 384 word-timed documents from four providers — most of them the same tracks answered by two
 * or three sources, which is the only way to tell a provider being wrong from a song being unusual.
 *
 * Every payload below is a verbatim excerpt of what a provider actually sent, cut to a few lines
 * and otherwise untouched. Both tracks are real cases from the archive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimingSanityTest {

    /**
     * 咏春 by 七朵组合 as Musixmatch richsync: one fragment per line, each holding eleven Chinese
     * characters. Musixmatch calls this word-synced. Across the archive 45 "syllables" covered
     * this song where NetEase used 467.
     */
    private val coarseRichsync = """
        [{"ts": 25.23, "te": 27.969, "l": [{"c": "拾一片落葉仰望新舊交疊", "o": 0}], "x": "拾一片落葉仰望新舊交疊"},
         {"ts": 28.61, "te": 31.304, "l": [{"c": "嫩綠自梢頭微微微微露臉", "o": 0}], "x": "嫩綠自梢頭微微微微露臉"},
         {"ts": 31.64, "te": 34.406, "l": [{"c": "被清風忽略花開得猶豫不決", "o": 0}], "x": "被清風忽略花開得猶豫不決"},
         {"ts": 34.78, "te": 38.075, "l": [{"c": "獨徘徊等你等你等你的一切", "o": 0}], "x": "獨徘徊等你等你等你的一切"},
         {"ts": 38.31, "te": 40.7239, "l": [{"c": "灑一地斑駁陽光穿過新葉", "o": 0}], "x": "灑一地斑駁陽光穿過新葉"}]
    """.trimIndent()

    /** The same five lines of the same song from NetEase, timed per character. */
    private val genuineYrc = """
        [25290,3140](25290,330,0)拾(25620,150,0)一(25770,180,0)片(25950,270,0)落(26220,580,0)叶(26800,150,0)仰(26950,160,0)望(27110,230,0)新(27340,170,0)旧(27510,310,0)交(27820,610,0)叠
        [28520,3150](28520,230,0)嫩(28750,210,0)绿(28960,110,0)自(29070,390,0)梢(29460,350,0)头(29810,380,0)微(30190,370,0)微(30560,330,0)微(30890,220,0)微(31110,150,0)露(31260,410,0)脸
        [31710,2990](31710,150,0)被(31860,230,0)清(32090,140,0)风(32230,360,0)忽(32590,350,0)略(32940,430,0)花(33370,330,0)开(33700,120,0)得(33820,260,0)犹(34080,160,0)豫(34240,180,0)不(34420,280,0)决
        [34890,3730](34890,150,0)独(35040,200,0)徘(35240,220,0)徊(35460,250,0)等(35710,570,0)你(36280,230,0)等(36510,560,0)你(37070,180,0)等(37250,230,0)你(37480,320,0)的(37800,450,0)一(38250,370,0)切
        [38620,2900](38620,250,0)洒(38870,160,0)一(39030,270,0)地(39300,310,0)斑(39610,180,0)驳(39790,320,0)阳(40110,260,0)光(40370,210,0)穿(40580,130,0)过(40710,250,0)新(40960,560,0)叶
    """.trimIndent()

    /**
     * Irony by LE SSERAFIM from NetEase — except it is not. These are the closing lines of some
     * other recording, timed at 5:31 to 5:48 against a track that is 2:24 long. In the archive
     * 74% of this document's syllables started after the song had already ended.
     */
    private val otherRecordingYrc = """
        [331910,3700](331910,810,0)手(332720,90,0)を(332810,390,0)伸(333200,220,0)ば(333420,270,0)し(333690,630,0)掠(334320,500,0)め(334820,790,0)た
        [335920,3500](335920,440,0)た(336360,380,0)く(336740,390,0)り(337130,290,0)寄(337420,300,0)せ(337720,240,0)る(337960,810,0)輪(338770,650,0)廻
        [339630,3760](339630,700,0)も(340330,130,0)う(340460,840,0)何(341300,560,0)も(341860,300,0)捨(342160,290,0)て(342450,280,0)な(342730,110,0)い(342840,550,0)で
    """.trimIndent()

    private val ironyDurationMs = 144_000L

    private fun document(lines: List<LyricLine>, provider: String) = LyricsDocument(
        kind = LyricsKind.SYLLABLE,
        lines = lines,
        providerName = provider,
        providerId = provider,
    )

    private fun richsync() =
        document(RichsyncParser.parse(coarseRichsync, 271_000)!!, "musixmatch")

    private fun yrc() = document(YrcParser.parse(genuineYrc), "netease")

    @Test
    fun `a fragment per line is not word timing, whatever the provider calls it`() {
        val coarse = richsync()
        // It really does claim the syllable tier: every line has a syllable in it.
        assertEquals(LyricsKind.SYLLABLE, coarse.kind)
        assertTrue(coarse.vocalLines.all { it.syllables.isNotEmpty() })

        // But not one of them can sweep, so it belongs in the tier it behaves like.
        assertTrue(coarse.vocalLines.none { TimingSanity.hasWordTimings(it) })
        assertEquals(LyricsKind.LINE, effectiveKind(coarse, bestLines = 0))
    }

    @Test
    fun `the same song timed per character keeps the syllable tier`() {
        val genuine = yrc()
        assertTrue(genuine.vocalLines.all { TimingSanity.hasWordTimings(it) })
        assertEquals(LyricsKind.SYLLABLE, effectiveKind(genuine, bestLines = 0))
    }

    @Test
    fun `and it wins even ranked last, because the tier is not a preference`() {
        // The reported complaint, in the one arrangement that proves the point: Musixmatch ranked
        // first and NetEase last. Real word timings still win, because coarse ones are not word
        // timings at all. Ranking was never reversed — it was being asked the wrong question.
        val answers = listOf("musixmatch" to richsync(), "netease" to yrc())
        val order = listOf("musixmatch", "netease")
        val bestLines = answers.maxOf { lineCount(it.second) }

        assertEquals("netease", pickBest(answers, order, bestLines)?.providerId)
    }

    @Test
    fun `a short line sung in one breath is still word-timed`() {
        // The exemption that keeps the check honest: a one-word line genuinely is one syllable,
        // and every source times it as one fragment.
        val line = LyricLine(
            role = LineRole.LEAD,
            startMs = 1_000,
            endMs = 1_800,
            text = "Oh",
            syllables = listOf(Syllable("Oh", 1_000, 1_800)),
        )
        assertTrue(TimingSanity.hasWordTimings(line))
    }

    @Test
    fun `timings from another recording are not this song's timings`() {
        val wrong = document(YrcParser.parse(otherRecordingYrc), "netease")
        assertTrue(TimingSanity.timingsOutrunTheTrack(wrong, ironyDurationMs))
        assertEquals(
            LyricsKind.LINE,
            TimingSanity.honestKind(wrong, ironyDurationMs).kind,
        )
    }

    @Test
    fun `a master a couple of seconds longer is left alone`() {
        // The case on the other side of the threshold, and the reason it is a share and not a
        // ratio: the archive's next-worst document ran 7 seconds past a 3:54 track and was a
        // perfectly good remaster. Here the excerpt's last line ends 1.7s past the duration.
        val genuine = yrc()
        assertFalse(TimingSanity.timingsOutrunTheTrack(genuine, trackDurationMs = 40_000))
        assertEquals(LyricsKind.SYLLABLE, TimingSanity.honestKind(genuine, 40_000).kind)
    }

    @Test
    fun `a track whose player never said how long it is gets the benefit of the doubt`() {
        val wrong = document(YrcParser.parse(otherRecordingYrc), "netease")
        assertFalse(TimingSanity.timingsOutrunTheTrack(wrong, trackDurationMs = 0))
        assertEquals(LyricsKind.SYLLABLE, TimingSanity.honestKind(wrong, 0).kind)
    }

    /**
     * Apple's unsynced lyrics, as it actually serves them: the same TTML as a synced track, with
     * `itunes:timing="None"` and not one `begin` attribute. 14 of the 339 Apple documents in the
     * archive looked like this.
     */
    private val untimedTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal"
            itunes:timing="None" xml:lang="en"><body>
        <div><p>You can just slide with him tonight</p><p>Just slide with him</p>
        <p>I know its over</p></div>
        <div><p>I’ll get a ride</p><p>On my own</p><p>Going home</p></div>
        </body></tt>
    """.trimIndent()

    @Test
    fun `lyrics with no timings are not line-synced lyrics`() {
        val parsed = TtmlParser.parse(untimedTtml, "Apple Music", "applemusic")!!

        // Every line lands on the same timestamp, because there is no timing in the document.
        assertEquals(1, parsed.vocalLines.map { it.startMs }.distinct().size)
        assertFalse(TimingSanity.hasUsableTimings(parsed))

        // Both layers agree: the parser takes Apple at its word, and the structural check would
        // have caught it even if the attribute were missing, which for some of the archive it was.
        assertEquals(LyricsKind.STATIC, parsed.kind)
        assertEquals(LyricsKind.STATIC, effectiveKind(parsed, bestLines = 0))
    }

    @Test
    fun `and so they lose to a source that actually has them`() {
        // The visible cost of getting this wrong: a full transcription that claims line timing
        // outranks one that admits it has none, and then never advances off the first line.
        val untimed = TtmlParser.parse(untimedTtml, "Apple Music", "applemusic")!!
        val timed = yrc()
        val answers = listOf("applemusic" to untimed, "netease" to timed)
        val bestLines = answers.maxOf { lineCount(it.second) }

        assertEquals("netease", pickBest(answers, listOf("applemusic", "netease"), bestLines)?.providerId)
    }

    @Test
    fun `the untimed verdict is recorded on the document, not just used to rank it`() {
        // The renderer reads `kind`, so a demotion that only happens inside the comparator is not a
        // demotion at all: the document would still be scrolled against a timestamp it repeats, and
        // the "not synced" notice would never appear. Every case in the archive declared
        // `timing="None"` and so was already handled by the parser, but the structural check exists
        // for the ones that do not — and it has to reach the document to be worth anything.
        val claimsLineSync = LyricsDocument(
            kind = LyricsKind.LINE,
            lines = listOf(
                LyricLine(role = LineRole.LEAD, startMs = 0, endMs = 0, text = "first line here"),
                LyricLine(role = LineRole.LEAD, startMs = 0, endMs = 0, text = "second line here"),
                LyricLine(role = LineRole.LEAD, startMs = 0, endMs = 0, text = "third line here"),
            ),
            providerName = "somewhere",
            providerId = "somewhere",
        )

        assertFalse(TimingSanity.hasUsableTimings(claimsLineSync))
        assertEquals(LyricsKind.STATIC, TimingSanity.honestKind(claimsLineSync, 180_000).kind)
    }

    @Test
    fun `a single line is not suspicious for having a single timestamp`() {
        val one = document(YrcParser.parse(genuineYrc).take(1), "netease")
        assertTrue(TimingSanity.hasUsableTimings(one))
    }

    @Test
    fun `a few syllables out of order are not grounds for demoting a document`() {
        // Deliberately not checked, and pinned here so it stays that way. It was the first thing
        // worth looking for and the archive ruled it out: where syllables run backwards at all it
        // is a handful in a document of hundreds — 8 of 483 in the worst case, 1.7% — and those
        // documents are otherwise the best answer available. No threshold separates them from good
        // ones, and the parsers already clamp the syllable so the sweep does not jump.
        val lines = YrcParser.parse(genuineYrc).mapIndexed { index, line ->
            if (index != 1) {
                line
            } else {
                // Drag one syllable back behind its predecessor, as one real AMLL document does.
                val broken = line.syllables.toMutableList()
                broken[4] = broken[4].copy(startMs = broken[3].startMs - 200)
                line.copy(syllables = broken)
            }
        }
        val nudged = document(lines, "netease")

        assertEquals(LyricsKind.SYLLABLE, effectiveKind(nudged, bestLines = 0))
        assertEquals(LyricsKind.SYLLABLE, TimingSanity.honestKind(nudged, 271_000).kind)
    }
}
