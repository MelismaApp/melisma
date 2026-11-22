package com.melisma.app.lyrics

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
        onDisk("direct", "spicylyrics", daysAgo(29))
        onDisk("relayed", "cacheserver", daysAgo(29), sourceUrl = "https://spicylyrics.org")
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
        onDisk("direct", "spicylyrics", daysAgo(29))

        assertNull(cache.get("direct"))
        // Reading it deleted it, so there is nothing left for the sweep.
        assertEquals(0, cache.sweep())
    }

    @Test
    fun `a deadline the document carries is kept, however recently the app saved it`() = runBlocking {
        // A clock the test moves, so the deadline passes when it says and not when CI gets round to it.
        var time = 1_800_000_000_000
        val clocked = LyricsCache(context) { time }
        // A server that had the words for four weeks before the phone asked: two days left, not a month.
        clocked.put("nearly", document("cacheserver", expiresAtMs = time + TimeUnit.DAYS.toMillis(2)))
        clocked.put("later", document("cacheserver", expiresAtMs = time + TimeUnit.DAYS.toMillis(20)))
        assertNotNull(clocked.get("nearly"))

        time += TimeUnit.DAYS.toMillis(3)

        assertEquals(1, clocked.sweep())
        assertNull(clocked.get("nearly"))
        assertNotNull(clocked.get("later"))
    }

    @Test
    fun `words whose time is already up are never written`() = runBlocking {
        cache.put("past", document("cacheserver", expiresAtMs = now - 1))
        // An upgrade that keeps an old age must not put capped words back either.
        cache.put("aged", document("spicylyrics"), savedAtMs = daysAgo(29))

        assertEquals(0, cache.sweep())
        assertNull(cache.get("past"))
        assertNull(cache.get("aged"))
    }

    /**
     * The sweep runs in another process, so a lookup can save a fresh answer between the sweep
     * reading an expired entry and deleting it. Both take the same lock, so whichever goes second
     * sees what the first one left: the fresh answer is there at the end, every time.
     */
    @Test
    fun `a fresh answer saved while the sweep runs is never the one deleted`() = runBlocking {
        repeat(60) { round ->
            onDisk("track", "spicylyrics", daysAgo(29))
            coroutineScope {
                launch(Dispatchers.IO) { LyricsCache(context).sweep() }
                launch(Dispatchers.IO) { LyricsCache(context).put("track", document("lrclib")) }
            }

            val held = cache.get("track") as? LyricsCache.Result.Hit
            assertEquals("round $round", "lrclib", held?.document?.providerId)
        }
    }

    @Test
    fun `a write leaves no half-written entry behind, and an abandoned one is cleared`() = runBlocking {
        val directory = java.io.File(context.cacheDir, "lyrics-cache")
        cache.put("track", document("lrclib"))
        // The entry, and nothing in between.
        assertEquals(listOf(true), directory.listFiles()!!.map { it.name.endsWith(".json") })

        val inFlight = java.io.File(directory, "x.json.1.pending").apply { writeText("{") }
        val abandoned = java.io.File(directory, "y.json.2.pending").apply {
            writeText("{")
            setLastModified(daysAgo(1))
        }
        assertEquals(0, cache.sweep())

        assertTrue(inFlight.exists())
        assertTrue(!abandoned.exists())
        assertNotNull(cache.get("track"))
    }

    private val lockFile get() = java.io.File(context.noBackupFilesDir, "lyrics-cache.lock")

    @Test
    fun `clearing the cache does not take the lock with it`() = runBlocking {
        cache.put("track", document("lrclib"))
        assertTrue(lockFile.exists())

        cache.clear()

        assertTrue(lockFile.exists())
        cache.put("track", document("lrclib"))
        assertNotNull(cache.get("track"))
    }

    /** A deadline that passes while a write waits for the lock: the words are not published late. */
    @Test
    fun `a deadline that passes during the write stops it, and leaves nothing behind`() = runBlocking {
        val start = 1_800_000_000_000
        // Each reading of the clock is a day later than the last: fine when the write begins,
        // past the deadline by the time the lock is held.
        var readings = 0
        val slow = LyricsCache(context) { start + TimeUnit.DAYS.toMillis(readings++.toLong()) }

        slow.put("track", document("cacheserver", expiresAtMs = start + TimeUnit.HOURS.toMillis(12)))

        assertTrue(java.io.File(context.cacheDir, "lyrics-cache").listFiles().orEmpty().isEmpty())
    }

    /** With no lock to be had the cache answers "nothing", and neither reads nor deletes unguarded. */
    @Test
    fun `when the lock cannot be taken the cache is a miss, not an error`() = runBlocking {
        cache.put("track", document("lrclib"))
        onDisk("capped", "spicylyrics", daysAgo(29))
        val directory = java.io.File(context.cacheDir, "lyrics-cache")
        val before = directory.listFiles()!!.map { it.name }.sorted()

        // A directory where the lock file should be: it can be neither opened nor locked.
        lockFile.delete()
        lockFile.mkdirs()
        try {
            assertNull(cache.get("track"))
            assertNull(cache.get("capped"))
            assertEquals(0, cache.sweep())
            cache.put("other", document("lrclib"))
            assertEquals(before, directory.listFiles()!!.map { it.name }.sorted())
        } finally {
            lockFile.deleteRecursively()
        }

        // And with the lock back, everything is as it was.
        assertNotNull(cache.get("track"))
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
