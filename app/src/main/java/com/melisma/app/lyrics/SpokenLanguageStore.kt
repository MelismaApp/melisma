package com.melisma.app.lyrics

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The language the cache server says each track is sung in, kept on the phone.
 *
 * Apart from the lyrics cache because the answer is about the track, not about whichever source's
 * words won, and because the server is only asked about a track once a month: without this, a tag
 * would be forgotten the moment the app restarted.
 *
 * Values are ISO 639-3 as the server sends them: `nan` for Hokkien, `zh`, `yue`.
 */
class SpokenLanguageStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val known = LinkedHashMap<String, String>()
    private var loaded = false

    fun get(trackKey: String): String? {
        load()
        return synchronized(known) { known[trackKey] }
    }

    fun put(trackKey: String, language: String) {
        load()
        val changed = synchronized(known) {
            if (known[trackKey] == language) return@synchronized false
            known.remove(trackKey)
            if (known.size >= CAPACITY) known.keys.firstOrNull()?.let(known::remove)
            known[trackKey] = language
            true
        }
        if (changed) save()
    }

    fun remove(trackKey: String) {
        load()
        val changed = synchronized(known) { known.remove(trackKey) != null }
        if (changed) save()
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val text = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() ?: return
        runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            synchronized(known) {
                for ((key, value) in root) value.jsonPrimitive.contentOrNull?.let { known[key] = it }
            }
        }
    }

    private fun save() {
        val snapshot = synchronized(known) { known.toMap() }
        runCatching { file.writeText(JsonObject(snapshot.mapValues { JsonPrimitive(it.value) }).toString()) }
    }

    private companion object {
        const val FILE_NAME = "spoken-languages.json"
        const val CAPACITY = 4_000
    }
}
