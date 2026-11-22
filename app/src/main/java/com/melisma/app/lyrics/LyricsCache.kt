package com.melisma.app.lyrics

import android.content.Context
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.provider.SpicyLyricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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
class LyricsCache(context: Context) {

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

        val entry = runCatching {
            json.decodeFromString<Entry>(file.readText())
        }.getOrNull() ?: run {
            file.delete()
            return@withContext null
        }

        // A remembered miss is dropped on sight rather than honoured. Written by an older
        // version, which cached "no lyrics" for two days; a track nobody had transcribed
        // then may well be in the community database now, and the only moment the user
        // cares is the moment they play it again.
        if (entry.document == null) {
            file.delete()
            return@withContext null
        }

        // Past its time. Words a source caps are gone, and that is the end of it. Anything else is
        // only old: still handed over, marked stale, so the caller can ask afresh when there is a
        // connection and keep showing these when there is not.
        val expired = System.currentTimeMillis() > expiryOf(entry.savedAtMs, entry.document)
        if (expired && isCapped(entry.document)) {
            file.delete()
            return@withContext null
        }

        // Words from a source the app no longer has are never served, however they got here. The
        // one-time sweep in MelismaApp clears the disk; this is what makes a lookup that races it, or
        // an upgrade that would write the old answer back, come to nothing.
        if (entry.document.providerId in RETIRED_PROVIDERS) {
            file.delete()
            return@withContext null
        }

        Result.Hit(
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
        val now = System.currentTimeMillis()
        runCatching {
            directory.mkdirs()
            fileFor(key).writeText(
                json.encodeToString(
                    Entry(
                        savedAtMs = savedAtMs ?: now,
                        document = document,
                        outcomes = outcomes.mapValues { (_, outcome) -> outcome.name },
                        outcomesAtMs = now,
                    ),
                ),
            )
            prune()
        }
        Unit
    }

    suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        fileFor(key).delete()
        Unit
    }

    /**
     * Delete every entry whose source caps how long it may be kept and whose time is up, and
     * return how many went.
     *
     * [get] already refuses such an entry, but only for a track that is played again; one that
     * never is would sit on disk indefinitely. Spicy Lyrics' terms make that a breach rather than
     * untidiness — a contributor's withdrawal only takes effect when stale copies are gone — so
     * the cap is enforced here, on a schedule, whether or not anything is looked up. See
     * [CacheSweepService].
     *
     * Only capped words. An old answer from any other source is left where it is: it is what a
     * song played with no connection still has to show.
     */
    suspend fun sweep(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var removed = 0
        directory.listFiles()?.forEach { file ->
            val entry = runCatching { json.decodeFromString<Entry>(file.readText()) }.getOrNull()
            val document = entry?.document
            // Unreadable, or a miss an older version stored: get() would delete it too.
            val gone = document == null || (isCapped(document) && now > expiryOf(entry.savedAtMs, document))
            if (gone && file.delete()) removed++
        }
        removed
    }

    /**
     * Drop every cached document that came from [providerId], and return how many went.
     *
     * For a source that is no longer part of the app: its words must not keep being shown from the
     * cache for a month after the integration is gone. What other sources said about those tracks
     * is kept, so the next lookup asks only where it needs to.
     */
    suspend fun forget(providerId: String): Int = withContext(Dispatchers.IO) {
        var removed = 0
        directory.listFiles()?.forEach { file ->
            val entry = runCatching { json.decodeFromString<Entry>(file.readText()) }.getOrNull()
            if (entry?.document?.providerId == providerId && file.delete()) removed++
        }
        removed
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        directory.listFiles()?.forEach { it.delete() }
        Unit
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        directory.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun prune() {
        val files = directory.listFiles() ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_ENTRIES)
            .forEach { it.delete() }
    }

    private fun fileFor(key: String): File = File(directory, "${sha1(key)}.json")

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

        /**
         * How long words from Spicy Lyrics are kept. Its terms say thirty days at the outside, and
         * the sweep that enforces it runs daily at Android's convenience, so the cap sits two days
         * inside the limit rather than on it.
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
