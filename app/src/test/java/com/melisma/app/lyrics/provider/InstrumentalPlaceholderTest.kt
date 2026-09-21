package com.melisma.app.lyrics.provider

import com.melisma.app.lyrics.parse.LrcParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NetEase says "no lyrics" by returning a lyric that says so.
 *
 * Found by the cache server cross-checking its archive: 13 of its 80 NetEase responses carry
 * 纯音乐，请欣赏 — "instrumental, please enjoy" — and for NewJeans' *OMG*, RADWIMPS' *Suzume*,
 * yally's *Party Party* and TV Girl's *The Blonde* it is the only line in the file. None of those
 * is an instrumental. Shown as-is it is a single line of Chinese where the words should be, and the
 * track is recorded as answered so nothing looks again.
 */
class InstrumentalPlaceholderTest {

    /** Verbatim, as NetEase sent it for NewJeans' *OMG*. */
    private val placeholderOnly = "[00:05.00]纯音乐，请欣赏"

    /** The longer wording NetEase also uses. */
    private val longerPlaceholder = "[00:00.00]此歌曲为没有填词的纯音乐，请您欣赏"

    private fun lines(lrc: String) = LrcParser.parse(lrc, 180_000).lines

    @Test
    fun `a lone instrumental placeholder is not lyrics`() {
        assertTrue(isInstrumentalPlaceholder(lines(placeholderOnly)))
    }

    @Test
    fun `nor is the longer wording`() {
        assertTrue(isInstrumentalPlaceholder(lines(longerPlaceholder)))
    }

    @Test
    fun `a real song is left alone`() {
        val real = """
            [00:12.34]拾一片落葉仰望新舊交疊
            [00:15.00]嫩綠自梢頭微微微微露臉
            [00:18.00]被清風忽略花開得猶豫不決
        """.trimIndent()
        assertFalse(isInstrumentalPlaceholder(lines(real)))
    }

    @Test
    fun `a song that merely mentions instrumental music keeps every line`() {
        // The check has to be about the whole document, not a line in it — otherwise a lyric about
        // 纯音乐 would delete the song.
        val mentions = """
            [00:12.34]我喜欢纯音乐，请欣赏
            [00:15.00]但是我也喜欢唱歌
            [00:18.00]一起来吧
        """.trimIndent()
        assertFalse(isInstrumentalPlaceholder(lines(mentions)))
    }

    @Test
    fun `nothing at all is not a placeholder`() {
        // "No lines" is already handled as no lyrics upstream; this must not claim it as its own.
        assertFalse(isInstrumentalPlaceholder(emptyList()))
    }
}
