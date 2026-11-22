package com.melisma.app.lyrics

import android.content.Context
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.provider.SpicyLyricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * On-disk lyrics cache.
 *
 * Skipping back one track should not cost a network round trip, and — more to the
 * point — the providers here are community-run or undocumented, so the polite thing
 * is to ask each of them once per track and then leave them alone.
 *
 * "Nothing found" is cached too, with a shorter life: without it, a track with no
 * lyrics re-queries every provider every time it comes round on shuffle.
 */
class LyricsCache(
    context: Context,
    /** The time, as epoch milliseconds. A parameter so a test can move it past a deadline. */
    private val now: () -> Long = System::currentTimeMillis,
) {

    @Serializable
    private data class Entry(
        val savedAtMs: Long,
        val document: LyricsDocument? = null,
        /**
         * What each source said, by provider id — see [Outcome].
         *
         * Kept because the cached document is only the *winner*, which says nothing about who else
         * was asked. Without this a track cached while a source was off, unconfigured or simply down
         * kept the poorer answer for the full thirty days, and nothing knew a better one had been
         * missed. Defaulted to empty so entries written by an older version still load.
         */
        val outcomes: Map<String, String> = emptyMap(),
        /**
         * When those outcomes were recorded, which is not when the document was saved.
         *
         * Separate so that re-asking a source does not extend the document's life: [savedAtMs] keeps
         * meaning "how old are these words", and the retry window is measured from here.
         */
        val outcomesAtMs: Long = 0L,
    )

    /**
     * How a source answered when it was last asked about a track.
     *
     * [NONE] is an answer and [FAILED] is not, which is the whole distinction: one is settled, the
     * other is worth trying again once whatever went wrong has had time to stop.
     */
    enum class Outcome { LYRICS, NONE, FAILED }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val directory: File = File(context.cacheDir, "lyrics-cache")

    suspend fun get(key: String): Result? = withContext(Dispatchers.IO) {
        val file = fileFor(key)
        if (!file.exists()) return@withContext null
        locked { read(file) }
    }

    /** [get], with the lock held: what it decides to delete is still the file it read. */
    private fun read(file: File): Result? {
        val entry = runCatching {
            json.decodeFromString<Entry>(file.readText())
        }.getOrNull() ?: run {
            file.delete()
            return null
        }

        // A remembered miss is dropped on sight rather than honoured. Written by an older
        // version, which cached "no lyrics" for two days; a track nobody had transcribed
        // then may well be in the community database now, and the only moment the user
        // cares is the moment they play it again.
        if (entry.document == null) {
            file.delete()
            return null
        }

        // Past its time. Words a source caps are gone, and that is the end of it. Anything else is
        // only old: still handed over, marked stale, so the caller can ask afresh when there is a
        // connection and keep showing these when there is not.
        val expired = now() > expiryOf(entry.savedAtMs, entry.document)
        if (expired && isCapped(entry.document)) {
            file.delete()
            return null
        }

        // Words from a source the app no longer has are never served, however they got here. The
        // one-time sweep in MelismaApp clears the disk; this is what makes a lookup that races it, or
        // an upgrade that would write the old answer back, come to nothing.
        if (entry.document.providerId in RETIRED_PROVIDERS) {
            file.delete()
            return null
        }

        return Result.Hit(
            document = entry.document,
            savedAtMs = entry.savedAtMs,
            outcomes = entry.outcomes.mapNotNull { (id, name) ->
                runCatching { Outcome.valueOf(name) }.getOrNull()?.let { id to it }
            }.toMap(),
            outcomesAtMs = entry.outcomesAtMs,
            stale = expired,
        )
    }

    /**
     * Remember a document. Nulls are deliberately not stored.
     *
     * Caching a miss saves a lookup and costs a song: the sources grow, and a track that
     * had nothing last week is worth asking about again the next time it plays. Asking once
     * per play of an untranscribed song is a cost worth paying — and it is bounded, because
     * a track only resolves once while it is playing.
     */
    suspend fun put(
        key: String,
        document: LyricsDocument?,
        outcomes: Map<String, Outcome> = emptyMap(),
        /** Null keeps the age of whatever is already stored — for an upgrade, which is not a new find. */
        savedAtMs: Long? = null,
    ) = withContext(Dispatchers.IO) {
        if (document == null) return@withContext
        val now = now()
        // Capped words whose time is already up are not written at all: a lookup that outlasted
        // the deadline, or an upgrade carrying an old age, must not put them back.
        if (isCapped(document) && now > expiryOf(savedAtMs ?: now, document)) return@withContext
        runCatching {
            directory.mkdirs()
            val target = fileFor(key)
            // Written beside the entry and moved into place, so a crash mid-write leaves the old
            // entry or the new one and never half of either.
            val pending = File(directory, "${target.name}.${System.nanoTime()}$PENDING")
            pending.writeText(
                json.encodeToString(
                    Entry(
                        savedAtMs = savedAtMs ?: now,
                        document = document,
                        outcomes = outcomes.mapValues { (_, outcome) -> outcome.name },
                        outcomesAtMs = now,
                    ),
                ),
            )
            try {
                locked {
                    // Checked again now the lock is held: waiting for it can outlast a deadline
                    // that was still ahead when the write began.
                    if (!isCapped(document) || now() <= expiryOf(savedAtMs ?: now, document)) {
                        Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            } finally {
                // Moved into place, it is no longer here; anything else, it must not stay.
                pending.delete()
            }
            prune()
        }
        Unit
    }

    /** Forget what is held for [key]. Under the lock like every other deletion. */
    suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        locked { fileFor(key).delete() }
        Unit
    }

    /**
     * Delete every entry whose source caps how long it may be kept and whose time is up, and
     * return how many went.
     *
     * [get] already refuses such an entry, but only for a track that is played again; one that
     * never is would sit on disk indefinitely. Spicy Lyrics' terms make that a breach rather than
     * untidiness — a contributor's withdrawal only takes effect when stale copies are gone — so
     * the cap is enforced here, each time the app starts, whether or not anything is looked up.
     *
     * Only capped words. An old answer from any other source is left where it is: it is what a
     * song played with no connection still has to show.
     */
    suspend fun sweep(): Int = withContext(Dispatchers.IO) {
        val now = now()
        var removed = 0
        directory.listFiles()?.forEach { file ->
            if (file.name.endsWith(PENDING)) {
                // A write in flight is left alone; one abandoned by a crash is cleared.
                if (now - file.lastModified() > ABANDONED_MS) file.delete()
                return@forEach
            }
            if (!file.name.endsWith(ENTRY)) return@forEach
            // Read and deleted under the lock a write takes to replace an entry, so the file
            // removed is the one that was judged and never a fresh answer saved in between.
            locked {
                if (!file.exists()) return@locked
                val entry = runCatching { json.decodeFromString<Entry>(file.readText()) }.getOrNull()
                val document = entry?.document
                // Unreadable, or a miss an older version stored: get() would delete it too.
                val gone = document == null || (isCapped(document) && now > expiryOf(entry.savedAtMs, document))
                if (gone && file.delete()) removed++
            }
        }
        removed
    }

    private var sweptAtMs: Long? = null

    /** [sweep], at most once a day: for a caller that asks every time the app is opened. */
    suspend fun sweepIfDue(): Int {
        val due = synchronized(this) {
            val now = now()
            val last = sweptAtMs
            (last == null || now - last !in 0 until SWEEP_INTERVAL_MS).also { if (it) sweptAtMs = now }
        }
        return if (due) sweep() else 0
    }

    /** Runs [block] with the cache's lock held, across every instance in the process. */
    private inline fun <T> locked(block: () -> T): T = synchronized(LOCK, block)

    /**
     * Drop every cached document that came from [providerId], and return how many went.
     *
     * For a source that is no longer part of the app: its words must not keep being shown from the
     * cache for a month after the integration is gone. What other sources said about those tracks
     * is kept, so the next lookup asks only where it needs to. Null when the pass was incomplete.
     */
    suspend fun forget(providerId: String): Int? = withContext(Dispatchers.IO) {
        // No directory is an empty cache; one that cannot be listed is a pass that did not happen.
        if (!directory.exists()) return@withContext 0
        val files = directory.listFiles() ?: return@withContext null
        var removed = 0
        var complete = true
        files.filter { it.name.endsWith(ENTRY) }.forEach { file ->
            runCatching {
                locked {
                    if (!file.exists()) return@locked
                    val entry = runCatching { json.decodeFromString<Entry>(file.readText()) }.getOrNull()
                    when {
                        // Unreadable, so whose words it holds cannot be told: it goes, as it
                        // would on being read.
                        entry == null -> if (!file.delete()) complete = false
                        entry.document?.providerId == providerId -> if (file.delete()) removed++ else complete = false
                    }
                }
            }.onFailure { complete = false }
        }
        // Null when an entry could not be looked at or removed, so the caller knows to come back.
        removed.takeIf { complete }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        directory.listFiles()?.forEach { it.delete() }
        Unit
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        directory.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun prune() {
        val files = directory.listFiles()?.filter { it.name.endsWith(ENTRY) } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_ENTRIES)
            .forEach { it.delete() }
    }

    private fun fileFor(key: String): File = File(directory, "${sha1(key)}$ENTRY")

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    sealed interface Result {
        data class Hit(
            val document: LyricsDocument,
            /** When the words were found, so an upgrade can keep the age rather than reset it. */
            val savedAtMs: Long = 0L,
            /** What each source said when this was cached. Empty for an entry written before this. */
            val outcomes: Map<String, Outcome> = emptyMap(),
            val outcomesAtMs: Long = 0L,
            /**
             * True when the entry is past its thirty days: worth replacing when the sources can be
             * reached, and still the right thing to show when they cannot.
             */
            val stale: Boolean = false,
        ) : Result
    }

    companion object {
        /** Sources the app has removed. What they left in the cache is dropped on sight. */
        val RETIRED_PROVIDERS = setOf("musixmatch")

        private val POSITIVE_TTL_MS = TimeUnit.DAYS.toMillis(30)

        private const val ENTRY = ".json"
        private const val PENDING = ".pending"
        private val LOCK = Any()
        private val ABANDONED_MS = TimeUnit.MINUTES.toMillis(10)
        private val SWEEP_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)

        /**
         * How long words from Spicy Lyrics are kept. Its terms say thirty days at the outside, and
         * the sweep that enforces it runs when the app starts, so the cap sits two days inside the
         * limit rather than on it.
         */
        private val SPICY_TTL_MS = TimeUnit.DAYS.toMillis(28)

        /**
         * Whether the words' source limits how long a copy may exist, as opposed to the cache
         * merely preferring a fresh one: Spicy Lyrics' words, or any a server sent with a deadline.
         */
        internal fun isCapped(document: LyricsDocument): Boolean =
            SpicyLyricsProvider.supplied(document) || document.expiresAtMs != null

        /**
         * When a document saved at [savedAtMs] is past its time: the cache's own thirty days, the
         * shorter life of Spicy Lyrics' words, or the moment the document itself names, whichever
         * is first.
         */
        internal fun expiryOf(savedAtMs: Long, document: LyricsDocument): Long {
            val life = if (SpicyLyricsProvider.supplied(document)) SPICY_TTL_MS else POSITIVE_TTL_MS
            return minOf(savedAtMs + life, document.expiresAtMs ?: Long.MAX_VALUE)
        }
        private const val MAX_ENTRIES = 500
    }
}
