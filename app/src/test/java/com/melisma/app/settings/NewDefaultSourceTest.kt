package com.melisma.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A source that joins the defaults after an install has its own list of enabled sources is offered
 * once — switched on for that install — and then left to the user like every other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewDefaultSourceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    // Cleared first: preferences outlive a test here, and what one test offered must not count for the next.
    private val prefs = context.getSharedPreferences("melisma", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun `an install from before the source existed gets it switched on, and keeps its own choices`() {
        // Written by a version without lrc.red, with Musixmatch deliberately switched off.
        prefs.edit().putStringSet(SettingsStore.KEY_PROVIDERS_ON, setOf("local", "cacheserver", "spotify", "amll", "netease", "lrclib")).commit()

        val enabled = SettingsStore(context).current.enabledProviders
        assertTrue(enabled.toString(), "lrcred" in enabled)
        assertFalse(enabled.toString(), "musixmatch" in enabled)
    }

    @Test
    fun `offered once - switching it off afterwards sticks`() {
        prefs.edit().putStringSet(SettingsStore.KEY_PROVIDERS_ON, setOf("local", "amll")).commit()
        val store = SettingsStore(context)
        assertTrue("lrcred" in store.current.enabledProviders)

        store.setProviderEnabled("lrcred", false)
        assertFalse("lrcred" in SettingsStore(context).current.enabledProviders)
    }

    @Test
    fun `a fresh install simply has the defaults`() {
        val enabled = SettingsStore(context).current.enabledProviders
        assertTrue("lrcred" in enabled)
        assertTrue(prefs.getStringSet(SettingsStore.KEY_PROVIDERS_OFFERED, null) == Settings.DEFAULT_ENABLED_PROVIDERS)
    }
}
