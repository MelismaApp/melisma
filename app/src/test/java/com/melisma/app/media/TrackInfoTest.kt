package com.melisma.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache key and the prefetch guard are the same subject seen twice: a prefetch is only
 * worth doing if the key it writes under is the key the track will be read back by.
 */
class TrackInfoTest {

    private fun track(
        title: String = "Lemon",
        artist: String = "Kenshi Yonezu",
        durationMs: Long = 255_000,
        mediaId: String? = null,
        packageName: String = "com.example.player",
    ) = TrackInfo(
        title = title,
        artist = artist,
        album = "",
        durationMs = durationMs,
        mediaId = mediaId,
        artworkUri = null,
        packageName = packageName,
    )

    @Test
    fun `a spotify media id becomes the key`() {
        val id = "7Cd17G3oNQ34OWUwS8ZxfR"
        assertEquals("sp:$id", track(mediaId = "spotify:track:$id").cacheKey)
    }

    @Test
    fun `a one-second reporting difference does not split the entry`() {
        assertEquals(track(durationMs = 255_000).cacheKey, track(durationMs = 255_900).cacheKey)
    }

    @Test
    fun `a queue entry with no duration is not worth prefetching`() {
        // Its key would be `lemon|kenshi yonezu|0`, which is not the key the track gets
        // once it starts playing — the request would be spent and never read back.
        assertFalse(track(durationMs = 0).isPrefetchable)
    }

    @Test
    fun `a duration or an id is enough to prefetch`() {
        assertTrue(track(durationMs = 255_000).isPrefetchable)
        assertTrue(
            track(durationMs = 0, mediaId = "spotify:track:7Cd17G3oNQ34OWUwS8ZxfR").isPrefetchable,
        )
    }

    @Test
    fun `a queue entry with no title is not worth anything`() {
        assertFalse(track(title = "", durationMs = 255_000).isPrefetchable)
    }

    // ---- the player's own id, as each player was seen to publish it ----------------

    @Test
    fun `deezer puts the queue index in front of the track id`() {
        assertEquals(
            PlayerId("deezer", "2981739661"),
            track(mediaId = "0.2981739661", packageName = "deezer.android.app").playerId,
        )
        assertEquals("deezer:2981739661", track(mediaId = "3.2981739661", packageName = "deezer.android.app").playerId.toString())
    }

    @Test
    fun `tidal and amazon publish the bare id`() {
        assertEquals(PlayerId("tidal", "134858527"), track(mediaId = "134858527", packageName = "com.aspiro.tidal").playerId)
        assertEquals(PlayerId("amazon", "B0DX1H5KT5"), track(mediaId = "B0DX1H5KT5", packageName = "com.amazon.mp3").playerId)
        assertEquals(
            PlayerId("soundcloud", "2417794317"),
            track(mediaId = "soundcloud:tracks:2417794317", packageName = "com.soundcloud.android").playerId,
        )
    }

    @Test
    fun `an id is only read from the player whose format it is`() {
        // A number from some other player is not a Tidal id.
        assertNull(track(mediaId = "134858527").playerId)
        assertNull(track(mediaId = "134858527", packageName = "deezer.android.app").playerId)
        // Nor is something Tidal publishes that is not a number.
        assertNull(track(mediaId = "abc", packageName = "com.aspiro.tidal").playerId)
        assertNull(track(mediaId = "fake:Lemon", packageName = "com.amazon.mp3").playerId)
        assertNull(track(mediaId = null, packageName = "com.aspiro.tidal").playerId)
    }

    @Test
    fun `spotify's id is not a player id, it has its own field`() {
        val spotify = track(mediaId = "spotify:track:7Cd17G3oNQ34OWUwS8ZxfR", packageName = "com.spotify.music")
        assertNull(spotify.playerId)
        assertEquals("7Cd17G3oNQ34OWUwS8ZxfR", spotify.spotifyTrackId)
    }

    @Test
    fun `a player id survives a round trip through text`() {
        assertEquals(PlayerId("tidal", "134858527"), PlayerId.parse("tidal:134858527"))
        assertNull(PlayerId.parse("tidal:"))
        assertNull(PlayerId.parse(":1"))
        assertNull(PlayerId.parse("nocolon"))
    }
}
