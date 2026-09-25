package com.melisma.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A song told which language it is stays told, and Auto forgets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChineseReadingSettingsTest {

    @Test
    fun `a song's reading is kept and Auto forgets it`() {
        val store = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
        // A key can contain anything a title can, including the characters used to store it.
        val key = "愛到明仔載\t|蔡佩軒|104"
        store.setChineseReading(key, ChineseReading.HOKKIEN)
        store.setChineseReading("sp:abc", ChineseReading.MANDARIN)
        assertEquals(ChineseReading.HOKKIEN, store.current.chineseReadings[key])
        assertEquals(ChineseReading.HOKKIEN, SettingsStore(ApplicationProvider.getApplicationContext<Context>()).current.chineseReadings[key])

        // Preferences are stored as XML, which cannot hold most control characters.
        val stored = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("melisma", Context.MODE_PRIVATE).getStringSet("chinese_readings", null)!!
        assertTrue(stored.all { entry -> entry.none { it < ' ' && it != '\t' } })

        store.setChineseReading(key, ChineseReading.AUTO)
        assertTrue(key !in store.current.chineseReadings)
        assertEquals(ChineseReading.MANDARIN, store.current.chineseReadings["sp:abc"])
    }
}
