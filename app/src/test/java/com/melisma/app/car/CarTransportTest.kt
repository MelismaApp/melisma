package com.melisma.app.car

import com.melisma.app.media.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which transport buttons a car screen gets.
 *
 * Both of the cases here are crashes rather than cosmetic slips, and neither is visible until a
 * particular player happens to be playing:
 *
 * - An empty action strip throws `IllegalStateException("Action strip must contain at least one
 *   action")`. A session advertising only `ACTION_SEEK_TO` has `Transport.any` true and earns no
 *   buttons at all, so the naive check produced exactly that — and took down both car templates for
 *   that one player.
 * - `PaneTemplate.setActionStrip` validates against `ACTIONS_CONSTRAINTS_SIMPLE`, which permits
 *   **one** action with a custom title and throws on the second. Three titled buttons — a normal
 *   player — meant the fallback screen could never render.
 */
class CarTransportTest {

    @Test
    fun `a normal player gets all three, in the order they are read`() {
        val buttons = carTransportFor(
            Transport(playPause = true, skipNext = true, skipPrevious = true, seek = true),
        )
        assertEquals(
            listOf(CarTransport.PREVIOUS, CarTransport.PLAY_PAUSE, CarTransport.NEXT),
            buttons,
        )
    }

    @Test
    fun `a seek-only session earns no strip at all`() {
        // The crash. `Transport.any` is true here because seeking is a capability, but there is no
        // seek button on a car screen — so the list has to come back empty and the caller has to
        // build no strip rather than an empty one.
        val buttons = carTransportFor(Transport(seek = true))
        assertTrue("a strip would be empty, and an empty strip throws", buttons.isEmpty())
    }

    @Test
    fun `a live stream gets play and pause and nothing else`() {
        assertEquals(listOf(CarTransport.PLAY_PAUSE), carTransportFor(Transport(playPause = true)))
    }

    @Test
    fun `a session that accepts nothing gets nothing`() {
        assertTrue(carTransportFor(Transport()).isEmpty())
    }

    @Test
    fun `at most one button may carry a title`() {
        // The other crash, as a rule rather than a comment. Only play/pause is titled, because it is
        // the one whose meaning changes; if a second ever gains one, PaneTemplate starts throwing and
        // the plain car screen stops rendering for every ordinary player.
        val titled = carTransportFor(
            Transport(playPause = true, skipNext = true, skipPrevious = true),
        ).filter { it == CarTransport.PLAY_PAUSE }

        assertEquals("ACTIONS_CONSTRAINTS_SIMPLE allows exactly one custom title", 1, titled.size)
    }
}
