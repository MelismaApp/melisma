package com.melisma.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.melisma.app.lyrics.LyricsCache
import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What an upgrade does about a source the app no longer has.
 *
 * Removing the code is the easy half. An installed copy still holds that source's credentials, its
 * words in the lyrics cache, and its id in the user's ranked list; each of those has to go too, or
 * the source lives on in everything but name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetiredSourceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("melisma", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences(SettingsStore.SECRETS_FILE, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        secrets.edit().clear().commit()
    }

    @Test
    fun `its credentials are deleted from both files on the next start`() {
        // Left by a version that still had the source, one in each place a token has ever lived.
        secrets.edit().putString("mxm_token", "anonymous").putString("mxm_user_token", "signed-in").commit()
        prefs.edit().putString("mxm_user_token", "pre-migration copy").commit()

        SettingsStore(context)

        for (key in SettingsStore.RETIRED_SECRET_KEYS) {
            assertFalse(key, secrets.contains(key))
            assertFalse(key, prefs.contains(key))
        }
        assertFalse(SettingsStore.SECRET_KEYS.any { it in SettingsStore.RETIRED_SECRET_KEYS })
    }

    @Test
    fun `its id is dropped from a stored order and a stored enabled set, so no row is drawn for it`() {
        prefs.edit()
            .putString(SettingsStore.KEY_PROVIDER_ORDER, "local,musixmatch,lrclib,amll")
            .putStringSet(SettingsStore.KEY_PROVIDERS_ON, setOf("local", "musixmatch", "lrclib"))
            .commit()

        val settings = SettingsStore(context).current

        assertFalse(settings.providerOrder.toString(), "musixmatch" in settings.providerOrder)
        assertFalse(settings.enabledProviders.toString(), "musixmatch" in settings.enabledProviders)
        // The user's own ranking of what remains is kept, with the newer sources appended.
        assertEquals(listOf("local", "lrclib", "amll"), settings.providerOrder.filter { it != "cacheserver" }.take(3))
        assertTrue(settings.providerOrder.containsAll(Settings.DEFAULT_PROVIDER_ORDER))
    }

    @Test
    fun `its words leave the lyrics cache, and nobody else's do`() = runBlocking {
        val cache = LyricsCache(context)
        cache.clear()
        fun document(provider: String) = LyricsDocument(
            kind = LyricsKind.LINE,
            lines = listOf(LyricLine(LineRole.LEAD, 0, 1000, "words")),
            providerName = provider,
            providerId = provider,
        )
        cache.put("track-a", document("musixmatch"), mapOf("musixmatch" to LyricsCache.Outcome.LYRICS))
        cache.put("track-b", document("lrclib"), mapOf("musixmatch" to LyricsCache.Outcome.NONE))
        cache.put("track-c", document("musixmatch"))

        assertEquals(2, cache.forget("musixmatch"))

        assertNull(cache.get("track-a"))
        assertNull(cache.get("track-c"))
        assertNotNull(cache.get("track-b"))
        // Nothing left to sweep: the second pass finds nothing and harms nothing.
        assertEquals(0, cache.forget("musixmatch"))
    }

    @Test
    fun `an entry that cannot be read is cleared too, and an empty cache is a finished pass`() = runBlocking {
        val cache = LyricsCache(context)
        cache.clear()
        val directory = java.io.File(context.cacheDir, "lyrics-cache").apply { mkdirs() }
        java.io.File(directory, "broken.json").writeText("{ not an entry")

        assertEquals(0, cache.forget("musixmatch"))
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".json") })

        directory.deleteRecursively()
        assertEquals(0, cache.forget("musixmatch"))
    }

    /**
     * The sweep runs in the background, and a lookup can get to a track before it does. Whatever is
     * read first, its words are not shown, and reading them is what removes them.
     */
    @Test
    fun `its words are refused on read, before any sweep`() = runBlocking {
        val cache = LyricsCache(context)
        cache.clear()
        cache.put(
            "track-d",
            LyricsDocument(LyricsKind.LINE, listOf(LyricLine(LineRole.LEAD, 0, 1000, "words")), "musixmatch", "musixmatch"),
        )

        assertNull(cache.get("track-d"))
        assertEquals(0, cache.forget("musixmatch"))
    }
}
