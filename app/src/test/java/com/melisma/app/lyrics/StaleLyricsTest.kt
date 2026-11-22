package com.melisma.app.lyrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A song played after its cached lyrics have aged out.
 *
 * Asked as "what if it expires and then someone plays the song offline — would there be no lyric
 * for it?" There would: an entry past thirty days was deleted the moment it was read, connection or
 * not. Old is now a reason to ask again, not a reason to have nothing; only words whose source
 * caps how long they may be kept are still removed outright.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StaleLyricsTest {

    private class Fake(override val id: String, override val displayName: String) : LyricsProvider {
        var fetches = 0
        var answer: (() -> LyricsDocument?)? = null
        override suspend fun fetch(request: LyricsRequest): LyricsDocument? {
            fetches++
            return answer?.invoke()
        }
    }

    private fun document(providerId: String, text: String) = LyricsDocument(
        kind = LyricsKind.LINE,
        lines = List(12) { LyricLine(LineRole.LEAD, it * 10_000, it * 10_000 + 9_000, "$text $it") },
        providerName = providerId,
        providerId = providerId,
    )

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: SettingsStore
    private val source = Fake("lrclib", "LRCLIB")
    private var failure: Throwable? = null
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failure = error },
    )
    private val track = TrackInfo(title = "Lemon", artist = "Kenshi Yonezu", album = "", durationMs = 130_000)
    private val key = LyricsRequest("Lemon", "Kenshi Yonezu", "", 130_000).cacheIdentity()
    private val seen = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun repository() = LyricsRepository(
        context = context,
        settingsStore = settings,
        cache = LyricsCache(context),
        romanizer = Romanizer(),
        translator = LyricsTranslator(),
        localStore = LocalLyricsStore(context),
        isrcStore = IsrcStore(context),
        spokenLanguages = SpokenLanguageStore(context),
        providers = listOf(source),
        scope = scope,
    ).also { repository ->
        scope.launch { repository.state.collect { seen += it.toString().take(60) } }
    }

    private fun online(connected: Boolean) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        if (connected) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
    }

    /** A copy cached [days] ago. */
    private fun cached(document: LyricsDocument, days: Long) = runBlocking {
        LyricsCache(context).put(key, document, savedAtMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days))
    }

    /**
     * An entry as it sits on disk, written directly: the cache itself refuses to save capped words
     * whose time is up, so one that has aged in place — or was left by an earlier version — can
     * only be put there this way.
     */
    private fun onDisk(key: String, providerId: String, savedAtMs: Long, sourceUrl: String? = null) {
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val link = sourceUrl?.let { ""","sourceUrl":"$it"""" }.orEmpty()
        java.io.File(context.cacheDir, "lyrics-cache").apply { mkdirs() }.resolve("$name.json").writeText(
            """{"savedAtMs":$savedAtMs,"document":{"kind":"LINE","lines":[{"role":"LEAD","startMs":0,"endMs":1000,""" +
                """"text":"words"}],"providerName":"$providerId","providerId":"$providerId"$link}}""",
        )
    }

    @Before
    fun freshPhone() {
        context.getSharedPreferences("melisma", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { LyricsCache(context).clear() }
        File(context.filesDir, "isrc-index.json").delete()
        settings = SettingsStore(context)
        settings.setIdentifyTracks(false)
        online(true)
    }

    @After
    fun stop() = scope.cancel()

    /** The first settled answer: lyrics, or a state saying why there are none. */
    private suspend fun LyricsRepository.settled(): LyricsState = try {
        withTimeout(10_000) { state.first { it !is LyricsState.Idle && it !is LyricsState.Loading } }
    } catch (timeout: TimeoutCancellationException) {
        failure?.let { throw AssertionError("the lookup threw", it) }
        throw AssertionError("nothing settled; fetches=${source.fetches}; seen=$seen", timeout)
    }

    private fun LyricsState.text() = (this as LyricsState.Loaded).document.lines.first().text

    @Test
    fun `with no connection, old lyrics are shown rather than nothing`() = runBlocking {
        cached(document("lrclib", "old"), days = 45)
        online(false)

        val repository = repository()
        repository.setTrack(track)

        assertEquals("old 0", repository.settled().text())
        assertEquals(0, source.fetches)
    }

    @Test
    fun `with a connection they are asked for again, and the new answer replaces them`() = runBlocking {
        cached(document("lrclib", "old"), days = 45)
        source.answer = { document("lrclib", "new") }

        val repository = repository()
        repository.setTrack(track)

        assertEquals("new 0", repository.settled().text())
        assertEquals(1, source.fetches)
        // And the replacement is a fresh entry, not another stale one.
        val held = LyricsCache(context).get(key) as LyricsCache.Result.Hit
        assertEquals("new 0", held.document.lines.first().text)
        assertEquals(false, held.stale)
    }

    @Test
    fun `asking again and finding nothing, or getting no answer, still shows the old ones`() = runBlocking {
        cached(document("lrclib", "old"), days = 45)
        source.answer = { null }
        val first = repository()
        first.setTrack(track)
        assertEquals("old 0", first.settled().text())
        assertEquals(1, source.fetches)

        source.answer = { throw java.io.IOException("no answer") }
        val second = repository()
        second.setTrack(track)
        assertEquals("old 0", second.settled().text())
        assertEquals(2, source.fetches)
    }

    /**
     * A source that names the recording while finding nothing sets off a second, forced lookup by
     * ISRC. That one skips the cache, and used to take the old lyrics off the screen with it.
     */
    @Test
    fun `the forced lookup that follows a late ISRC keeps them too`() = runBlocking {
        cached(document("lrclib", "old"), days = 45)
        val asked = ArrayList<String?>()
        val naming = object : LyricsProvider {
            override val id = "amll"
            override val displayName = "AMLL"
            override suspend fun fetch(request: LyricsRequest): LyricsDocument? {
                synchronized(asked) { asked += request.isrc }
                if (request.isrc == null) request.onIsrc?.invoke("JPU901900876")
                return null
            }
        }
        val repository = LyricsRepository(
            context = context,
            settingsStore = settings,
            cache = LyricsCache(context),
            romanizer = Romanizer(),
            translator = LyricsTranslator(),
            localStore = LocalLyricsStore(context),
            isrcStore = IsrcStore(context),
            spokenLanguages = SpokenLanguageStore(context),
            providers = listOf(naming),
            scope = scope,
        )
        repository.setTrack(track)

        withTimeout(10_000) { while (synchronized(asked) { asked.size } < 2) kotlinx.coroutines.delay(20) }
        assertEquals(listOf(null, "JPU901900876"), synchronized(asked) { asked.toList() })
        kotlinx.coroutines.delay(300)
        assertEquals("old 0", repository.settled().text())
    }

    @Test
    fun `Spicy Lyrics' words past their limit are not shown, connection or not`() = runBlocking {
        onDisk(key, "spicylyrics", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(29))
        online(false)

        val repository = repository()
        repository.setTrack(track)

        assertTrue(repository.settled() is LyricsState.Offline)
        // Reading it was what removed it.
        assertEquals(0, LyricsCache(context).sweep())
        assertTrue(File(context.cacheDir, "lyrics-cache").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a fresh answer is shown from the cache without asking anyone`() = runBlocking {
        cached(document("lrclib", "fresh"), days = 3)
        // A source that answers nothing, so anything shown can only have come from the cache.
        source.answer = { null }

        val repository = repository()
        repository.setTrack(track)

        assertEquals("fresh 0", repository.settled().text())
    }
}
