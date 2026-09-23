package com.melisma.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the cache server is asked for the extras, and when it is the only thing asked. */
class CacheServerSettingsTest {

    private val server = Settings(
        developerMode = true,
        cacheServerUrl = "http://192.168.1.2:8080",
        cacheServerExtras = true,
    )

    @Test
    fun `the extras come from the server alone only when asked for`() {
        assertTrue(server.cacheServerExtrasActive)
        assertFalse(server.cacheServerExtrasOnly)
        assertTrue(server.copy(cacheServerExtrasMode = ExtrasServerMode.ONLY).cacheServerExtrasOnly)
    }

    @Test
    fun `the lyrics mode does not reach the extras`() {
        val lyricsOnly = server.copy(cacheServerMode = CacheServerMode.ONLY)
        assertTrue(lyricsOnly.cacheServerExtrasActive)
        assertFalse(lyricsOnly.cacheServerExtrasOnly)
    }

    @Test
    fun `server-only extras need the server to be in use at all`() {
        val only = server.copy(cacheServerExtrasMode = ExtrasServerMode.ONLY)
        // Otherwise the phone would stop asking its own tokens with nothing to ask instead.
        assertFalse(only.copy(cacheServerExtras = false).cacheServerExtrasOnly)
        assertFalse(only.copy(developerMode = false).cacheServerExtrasOnly)
        assertFalse(only.copy(cacheServerUrl = " ").cacheServerExtrasOnly)
    }
}
