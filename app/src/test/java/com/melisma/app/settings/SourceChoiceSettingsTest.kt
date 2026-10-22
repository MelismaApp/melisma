package com.melisma.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceChoiceSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a choice is kept by track and read back by a new store`() {
        val store = SettingsStore(context)
        store.setSourceChoice("lemon|kenshi yonezu|127", "spotify")
        store.setSourceChoice("sp:7Cd17G3oNQ34OWUwS8ZxfR", "lrclib")

        val reread = SettingsStore(context).current.sourceChoices
        assertEquals("spotify", reread["lemon|kenshi yonezu|127"])
        assertEquals("lrclib", reread["sp:7Cd17G3oNQ34OWUwS8ZxfR"])
    }

    @Test
    fun `automatic forgets the track's choice and nobody else's`() {
        val store = SettingsStore(context)
        store.setSourceChoice("a|b|1", "spotify")
        store.setSourceChoice("c|d|2", "amll")
        store.setSourceChoice("a|b|1", null)

        assertNull(store.current.sourceChoices["a|b|1"])
        assertEquals("amll", store.current.sourceChoices["c|d|2"])
    }
}
