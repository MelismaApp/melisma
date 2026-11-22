package com.melisma.app.lyrics

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * What becomes of a cached copy once its time is up, which depends on whose words they are.
 *
 * Raised by Spicy Lyrics' review of the app: the thirty-day limit was only checked on lookup, so a
 * file for a song nobody returned to sat on disk past it. Its words are now deleted on a schedule.
 * Every other source's are only old: kept, so a song played with no connection still has them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheExpiryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cache = LyricsCache(context)
    private val now get() = System.currentTimeMillis()

    private fun daysAgo(days: Long) = now - TimeUnit.DAYS.toMillis(days)

    private fun document(
        providerId: String,
        sourceUrl: String? = null,
        expiresAtMs: Long? = null,
    ) = LyricsDocument(
        kind = LyricsKind.LINE,
        lines = listOf(LyricLine(LineRole.LEAD, 0, 1000, "words")),
        providerName = providerId,
        providerId = providerId,
        sourceUrl = sourceUrl,
        expiresAtMs = expiresAtMs,
    )

    @Before
    fun setUp() = runBlocking { cache.clear() }

    @Test
    fun `another source's old answer is kept and marked stale, not deleted`() = runBlocking {
        cache.put("old", document("lrclib"), savedAtMs = daysAgo(31))
        cache.put("fresh", document("lrclib"), savedAtMs = daysAgo(29))

        assertEquals(0, cache.sweep())

        assertEquals(false, (cache.get("fresh") as LyricsCache.Result.Hit).stale)
        // Still there after being read, too: reading it is not what removes it.
        assertEquals(true, (cache.get("old") as LyricsCache.Result.Hit).stale)
        assertEquals(true, (cache.get("old") as LyricsCache.Result.Hit).stale)
    }

    @Test
    fun `Spicy Lyrics' words are deleted two days inside its thirty, with nothing looked up`() = runBlocking {
        cache.put("direct", document("spicylyrics"), savedAtMs = daysAgo(29))
        cache.put("relayed", document("cacheserver", sourceUrl = "https://spicylyrics.org"), savedAtMs = daysAgo(29))
        cache.put("direct-young", document("spicylyrics"), savedAtMs = daysAgo(27))
        // The same age from a source with no such limit stays.
        cache.put("other", document("cacheserver", sourceUrl = "https://lrc.red"), savedAtMs = daysAgo(29))

        assertEquals(2, cache.sweep())

        assertNotNull(cache.get("direct-young"))
        assertNotNull(cache.get("other"))
        assertEquals(0, cache.sweep())
    }

    @Test
    fun `and they are refused on read, never handed over as stale`() = runBlocking {
        cache.put("direct", document("spicylyrics"), savedAtMs = daysAgo(29))

        assertNull(cache.get("direct"))
        // Reading it deleted it, so there is nothing left for the sweep.
        assertEquals(0, cache.sweep())
    }

    @Test
    fun `a deadline the document carries is kept, however recently the app saved it`() = runBlocking {
        // A server that had the words for four weeks before the phone asked: two days left, not a month.
        cache.put("nearly", document("cacheserver", expiresAtMs = now + TimeUnit.DAYS.toMillis(2)))
        cache.put("past", document("cacheserver", expiresAtMs = now - 1))

        assertNotNull(cache.get("nearly"))
        // Refused on read as well as swept, so it is never shown in between.
        assertNull(cache.get("past"))
        cache.put("past", document("cacheserver", expiresAtMs = now - 1))
        assertEquals(1, cache.sweep())
    }

    @Test
    fun `keeping an entry's age through an upgrade does not extend its life`() = runBlocking {
        val saved = daysAgo(27)
        cache.put("track", document("spicylyrics"), savedAtMs = saved)
        val hit = cache.get("track") as LyricsCache.Result.Hit
        // What the repository does when it re-asks the other sources and keeps the same words.
        cache.put("track", hit.document, hit.outcomes, savedAtMs = hit.savedAtMs)

        assertEquals(saved, (cache.get("track") as LyricsCache.Result.Hit).savedAtMs)
        assertEquals(saved + TimeUnit.DAYS.toMillis(28), LyricsCache.expiryOf(saved, hit.document))
    }

    @Test
    fun `the sweep is scheduled once, daily, and survives a restart`() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()

        CacheSweepService.schedule(context)
        CacheSweepService.schedule(context)

        val job = scheduler.allPendingJobs.single()
        assertEquals(CacheSweepService.JOB_ID, job.id)
        assertEquals(CacheSweepService::class.java.name, job.service.className)
        assertTrue(job.isPeriodic)
        assertEquals(TimeUnit.DAYS.toMillis(1), job.intervalMillis)
        assertTrue(job.isPersisted)
    }
}
