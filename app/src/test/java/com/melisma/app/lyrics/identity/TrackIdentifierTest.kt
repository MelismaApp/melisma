package com.melisma.app.lyrics.identity

import com.melisma.app.lyrics.provider.LyricsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackIdentifierTest {

    /** The shape of `GET /track/<id>`, cut down to what is read. */
    private fun deezer(isrc: String? = "KRA302400093", duration: Int? = 196) = buildString {
        append("""{"id":2981739661,"title":"Armageddon",""")
        if (isrc != null) append(""""isrc":"$isrc",""")
        if (duration != null) append(""""duration":$duration,""")
        append(""""artist":{"name":"aespa"},"album":{"id":639291471}}""")
    }

    @Test
    fun `the ISRC is read when the length agrees`() {
        assertEquals("KRA302400093", TrackIdentifier.deezerIsrc(deezer(), durationMs = 196_000))
        // Deezer gives whole seconds and the player milliseconds, so a little either way is fine.
        assertEquals("KRA302400093", TrackIdentifier.deezerIsrc(deezer(), durationMs = 198_400))
        assertEquals("KRA302400093", TrackIdentifier.deezerIsrc(deezer(), durationMs = 193_100))
    }

    @Test
    fun `a different length is a different recording`() {
        assertNull(TrackIdentifier.deezerIsrc(deezer(), durationMs = 210_000))
        assertNull(TrackIdentifier.deezerIsrc(deezer(), durationMs = 180_000))
    }

    @Test
    fun `an unknown length on either side is not held against it`() {
        assertEquals("KRA302400093", TrackIdentifier.deezerIsrc(deezer(), durationMs = 0))
        assertEquals("KRA302400093", TrackIdentifier.deezerIsrc(deezer(duration = null), durationMs = 196_000))
    }

    @Test
    fun `the ISRC is normalised, and anything that is not one is refused`() {
        assertEquals("KRA302400093", TrackIdentifier.deezerIsrc(deezer(isrc = " kra302400093 "), 196_000))
        assertNull(TrackIdentifier.deezerIsrc(deezer(isrc = ""), 196_000))
        assertNull(TrackIdentifier.deezerIsrc(deezer(isrc = "KRA3024"), 196_000))
        assertNull(TrackIdentifier.deezerIsrc(deezer(isrc = null), 196_000))
    }

    @Test
    fun `an error body or no body at all is nothing`() {
        assertNull(TrackIdentifier.deezerIsrc("""{"error":{"type":"DataException","message":"no data"}}""", 196_000))
        assertNull(TrackIdentifier.deezerIsrc("<html>busy</html>", 196_000))
        assertNull(TrackIdentifier.deezerIsrc("", 196_000))
    }

    // ---- by name --------------------------------------------------------------------

    private val kaiju = LyricsRequest(title = "怪獣", artist = "サカナクション", album = "怪獣", durationMs = 252_722)

    /** MusicBrainz's recording search, as it answered for 怪獣: three recordings, one with an ISRC. */
    private val musicBrainz = """{"recordings":[
        {"id":"93f8ad69","score":100,"title":"怪獣","artist-credit":[{"name":"サカナクション","artist":{"name":"サカナクション","sort-name":"Sakanaction"}}]},
        {"id":"b36fdc7a","score":100,"title":"怪獣","length":252000,"isrcs":["JPVI02403562"],
         "artist-credit":[{"name":"サカナクション","artist":{"name":"サカナクション","sort-name":"Sakanaction"}}]},
        {"id":"f3e14f69","score":90,"title":"怪獣","length":309666,"isrcs":["JPVI02403570"],
         "artist-credit":[{"name":"サカナクション","artist":{"name":"サカナクション","sort-name":"Sakanaction"}}]}
    ]}"""

    /** The same answer with the artist in Japanese only, so a Latin name has nothing to compare with. */
    private val japaneseOnly = musicBrainz.replace(""","sort-name":"Sakanaction"""", "")

    @Test
    fun `the recording whose names and length agree is taken, not the first`() {
        assertEquals("JPVI02403562", TrackIdentifier.musicBrainzIsrc(musicBrainz, kaiju))
    }

    @Test
    fun `a recording without an ISRC, or of another length, is not an answer`() {
        // The 309 s recording is the only one left once the 252 s one is gone, and it is too long.
        val without = musicBrainz.replace(""""length":252000,"isrcs":["JPVI02403562"],""", "")
        assertNull(TrackIdentifier.musicBrainzIsrc(without, kaiju))
        // An unknown length on the player's side is not enough to remember a search result by.
        assertNull(TrackIdentifier.musicBrainzIsrc(musicBrainz, kaiju.copy(durationMs = 0)))
    }

    @Test
    fun `another artist's song of the same name and length is refused`() {
        // Compared through the sort-name, which is the spelling the player's Latin name can meet.
        assertNull(TrackIdentifier.musicBrainzIsrc(musicBrainz, kaiju.copy(artist = "Vaundy")))
        assertEquals("JPVI02403562", TrackIdentifier.musicBrainzIsrc(musicBrainz, kaiju.copy(artist = "Sakanaction")))
    }

    @Test
    fun `when no spelling of the artist can be compared, only an exact title and length will do`() {
        val latin = kaiju.copy(artist = "Sakanaction")
        assertEquals("JPVI02403562", TrackIdentifier.musicBrainzIsrc(japaneseOnly, latin))
        // 722 ms off is exact enough; two seconds is not, though it would be with the artist known.
        assertNull(TrackIdentifier.musicBrainzIsrc(japaneseOnly, latin.copy(durationMs = 254_000)))
        assertEquals("JPVI02403562", TrackIdentifier.musicBrainzIsrc(musicBrainz, latin.copy(durationMs = 254_000)))
        // And the title has to be the same song's, not a close one.
        assertNull(TrackIdentifier.musicBrainzIsrc(japaneseOnly.replace("\"title\":\"怪獣\"", "\"title\":\"怪獣 2\""), latin))
    }

    @Test
    fun `an ISRC written with hyphens is read`() {
        val hyphenated = musicBrainz.replace("JPVI02403562", "JP-VI0-24-03562")
        assertEquals("JPVI02403562", TrackIdentifier.musicBrainzIsrc(hyphenated, kaiju))
    }

    /** Deezer's search for YOASOBI 夜に駆ける: the song, a live take and a music-box cover. */
    private val deezerSearch = """{"data":[
        {"id":824731152,"title":"夜に駆ける","duration":261,"artist":{"name":"YOASOBI"},"album":{"title":"夜に駆ける"}},
        {"id":2450202945,"title":"夜に駆ける - From THE FIRST TAKE","duration":248,"artist":{"name":"YOASOBI"}},
        {"id":4171849732,"title":"夜に駆ける (YOASOBI)","duration":301,"artist":{"name":"コロムビアオルゴール"}}
    ],"total":22}"""

    private val yoru = LyricsRequest(title = "夜に駆ける", artist = "YOASOBI", album = "THE BOOK", durationMs = 259_000)

    @Test
    fun `the search result whose length agrees is taken`() {
        assertEquals("824731152", TrackIdentifier.deezerSearchId(deezerSearch, yoru))
        // A player reporting the live take's length gets the live take.
        assertEquals("2450202945", TrackIdentifier.deezerSearchId(deezerSearch, yoru.copy(durationMs = 248_000)))
    }

    @Test
    fun `a search that matches nothing closely enough is nothing`() {
        assertNull(TrackIdentifier.deezerSearchId(deezerSearch, yoru.copy(durationMs = 180_000)))
        assertNull(TrackIdentifier.deezerSearchId(deezerSearch, yoru.copy(title = "群青")))
        assertNull(TrackIdentifier.deezerSearchId("""{"data":[],"total":0}""", yoru))
        assertNull(TrackIdentifier.deezerSearchId("""{"error":{"code":4}}""", yoru))
    }
}
