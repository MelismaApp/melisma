package com.melisma.app.ui.background

import com.melisma.app.settings.CanvasMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a portrait Canvas goes, so a landscape screen never shows it as a four-times crop. */
class CanvasFramingTest {

    @Test
    fun `portrait fills, in either mode`() {
        for (mode in listOf(CanvasMode.FULL, CanvasMode.BLURRED)) {
            assertEquals(CanvasFraming.FILL, canvasFraming(landscape = false, mode = mode, cinemaPanel = true))
            assertEquals(CanvasFraming.FILL, canvasFraming(landscape = false, mode = mode, cinemaPanel = false))
        }
    }

    @Test
    fun `blurred fills in landscape too, since the crop does not show`() {
        assertEquals(CanvasFraming.FILL, canvasFraming(landscape = true, mode = CanvasMode.BLURRED, cinemaPanel = true))
        assertEquals(CanvasFraming.FILL, canvasFraming(landscape = true, mode = CanvasMode.BLURRED, cinemaPanel = false))
    }

    @Test
    fun `full in landscape takes Cinema's panel, or else the middle`() {
        assertEquals(CanvasFraming.CARD, canvasFraming(landscape = true, mode = CanvasMode.FULL, cinemaPanel = true))
        assertEquals(CanvasFraming.COLUMN, canvasFraming(landscape = true, mode = CanvasMode.FULL, cinemaPanel = false))
    }
}
