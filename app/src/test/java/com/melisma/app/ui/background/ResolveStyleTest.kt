package com.melisma.app.ui.background

import com.melisma.app.settings.BackgroundStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which background is drawn once the fallbacks and the still-background rules are applied. */
class ResolveStyleTest {

    private fun resolve(
        style: BackgroundStyle,
        still: Boolean = false,
        asCover: Boolean = false,
        artist: Boolean = true,
    ) = resolveStyle(style, preferStill = still, stillAsCover = asCover, hasArtistImage = artist)

    @Test
    fun `Living freezes in place when held still`() {
        assertEquals(BackgroundStyle.ANIMATED, resolve(BackgroundStyle.ANIMATED, still = true))
        assertEquals(BackgroundStyle.ANIMATED, resolve(BackgroundStyle.AUTO, still = true))
    }

    @Test
    fun `Living becomes the cover when held still and asked to`() {
        assertEquals(BackgroundStyle.COVER_ART, resolve(BackgroundStyle.ANIMATED, still = true, asCover = true))
        assertEquals(BackgroundStyle.COVER_ART, resolve(BackgroundStyle.AUTO, still = true, asCover = true))
    }

    @Test
    fun `asking for the cover does nothing while nothing is held still`() {
        assertEquals(BackgroundStyle.ANIMATED, resolve(BackgroundStyle.ANIMATED, asCover = true))
        assertEquals(BackgroundStyle.ANIMATED, resolve(BackgroundStyle.AUTO, asCover = true))
    }

    @Test
    fun `classic always swaps for the cover, having no still form`() {
        assertEquals(BackgroundStyle.COVER_ART, resolve(BackgroundStyle.LIVING_CLASSIC, still = true))
        assertEquals(BackgroundStyle.LIVING_CLASSIC, resolve(BackgroundStyle.LIVING_CLASSIC))
    }

    @Test
    fun `the still styles are untouched`() {
        for (style in listOf(BackgroundStyle.COVER_ART, BackgroundStyle.COLOR, BackgroundStyle.BLACK)) {
            assertEquals(style, resolve(style, still = true, asCover = true))
        }
    }

    @Test
    fun `no artist image falls back to the cover`() {
        assertEquals(BackgroundStyle.COVER_ART, resolve(BackgroundStyle.ARTIST_HEADER, artist = false))
        assertEquals(BackgroundStyle.ARTIST_HEADER, resolve(BackgroundStyle.ARTIST_HEADER))
    }
}
