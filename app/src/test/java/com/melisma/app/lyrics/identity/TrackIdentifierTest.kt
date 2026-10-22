package com.melisma.app.lyrics.identity

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
}
