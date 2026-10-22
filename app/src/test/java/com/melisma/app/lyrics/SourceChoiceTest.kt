package com.melisma.app.lyrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.lyrics.model.Syllable
import com.melisma.app.lyrics.provider.LocalLyricsStore
import com.melisma.app.lyrics.provider.LyricsProvider
import com.melisma.app.lyrics.provider.LyricsRequest
import com.melisma.app.lyrics.romanize.Romanizer
import com.melisma.app.lyrics.translate.LyricsTranslator
import com.melisma.app.media.IsrcStore
import com.melisma.app.media.TrackInfo
import com.melisma.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Choosing a source for one track, over the ranking.
 *
 * Two fake sources: one word-synced, which the ranking always prefers, and one line-synced. The
 * choice has to show the line-synced one, go back to the ranking's answer without asking anyone
 * again, survive into the next play, and fall back when the chosen source has nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceChoiceTest {

    private class Fake(
        override val id: String,
        override val displayName: String,
        var document: LyricsDocument?,
    ) : LyricsProvider {
        var fetches = 0
        override val canBeWordSynced = true
        override suspend fun fetch(request: LyricsRequest): LyricsDocument? {
            fetches++
            return document
        }
    }

    private fun document(provider: Fake, kind: LyricsKind) = LyricsDocument(
        kind = kind,
        lines = List(12) { index ->
            val start = index * 10_000
            LyricLine(
                role = LineRole.LEAD,
                startMs = start,
                endMs = start + 9_000,
                text = "line $index",
                syllables = if (kind == LyricsKind.SYLLABLE) {
                    listOf(Syllable("line", start, start + 4_000), Syllable(" $index", start + 4_000, start + 9_000))
                } else {
                    emptyList()
                },
            )
        },
        providerName = provider.displayName,
        providerId = provider.id,
    )

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: SettingsStore
    private val word = Fake("musixmatch", "Musixmatch", null).also { it.document = document(it, LyricsKind.SYLLABLE) }
    private val line = Fake("lrclib", "LRCLIB", null).also { it.document = document(it, LyricsKind.LINE) }
    /** Whatever a lookup threw, so a test fails with the cause rather than a timeout. */
    private var failure: Throwable? = null
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failure = error },
    )
    private val track = TrackInfo(title = "Lemon", artist = "Kenshi Yonezu", album = "", durationMs = 130_000)

    private fun repository() = LyricsRepository(
        context = context,
        settingsStore = settings,
        cache = LyricsCache(context),
        romanizer = Romanizer(),
        translator = LyricsTranslator(),
        localStore = LocalLyricsStore(context),
        isrcStore = IsrcStore(context),
        spokenLanguages = SpokenLanguageStore(context),
        providers = listOf(word, line),
        scope = scope,
    )

    @Before
    fun freshPhone() {
        // Preferences and the lyrics cache outlive a test here, and a choice or a cached answer
        // left by one test is exactly what the next one must not start from.
        context.getSharedPreferences("melisma", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { LyricsCache(context).clear() }
        settings = SettingsStore(context)

        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
    }

    @After
    fun stop() = scope.cancel()

    /** The next answer from [from]. */
    private suspend fun LyricsRepository.shown(from: String): LyricsDocument = try {
        withTimeout(10_000) {
            (state.first { it is LyricsState.Loaded && it.document.providerId == from } as LyricsState.Loaded).document
        }
    } catch (timeout: TimeoutCancellationException) {
        failure?.let { throw AssertionError("the lookup threw", it) }
        throw AssertionError("still ${state.value} after 10 s waiting for $from", timeout)
    }

    @Test
    fun `the ranking's answer shows until a source is chosen, and again when the choice is cleared`() = runBlocking {
        val repository = repository()
        repository.setTrack(track)
        assertEquals(LyricsKind.SYLLABLE, repository.shown("musixmatch").kind)

        repository.chooseSource("lrclib")
        assertEquals(LyricsKind.LINE, repository.shown("lrclib").kind)
        assertEquals("lrclib", settings.current.sourceChoices[track.cacheKey])

        // Back to the ranking's answer from the cache: nobody is asked again.
        val asked = word.fetches
        repository.chooseSource(null)
        repository.shown("musixmatch")
        assertEquals(asked, word.fetches)
        assertNull(settings.current.sourceChoices[track.cacheKey])
    }

    @Test
    fun `the choice is remembered for the next play`() = runBlocking {
        val first = repository()
        first.setTrack(track)
        first.shown("musixmatch")
        first.chooseSource("lrclib")
        first.shown("lrclib")

        val asked = line.fetches
        val next = repository()
        next.setTrack(track)
        next.shown("lrclib")
        // From the cache: the chosen source was not asked again either.
        assertEquals(asked, line.fetches)
    }

    @Test
    fun `a chosen source with nothing for the track leaves the best answer showing`() = runBlocking {
        line.document = null
        val repository = repository()
        repository.setTrack(track)
        repository.shown("musixmatch")

        repository.chooseSource("lrclib")
        // Loading, then the ranking's answer again — and the choice is kept, so the row can say why.
        withTimeout(10_000) { repository.state.first { it is LyricsState.Loading } }
        assertEquals("musixmatch", repository.shown("musixmatch").providerId)
        assertEquals("lrclib", settings.current.sourceChoices[track.cacheKey])
    }
}
