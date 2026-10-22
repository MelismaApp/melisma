package com.melisma.app.media

import android.graphics.Bitmap
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionReportTest {

    private val session = MediaSession(ApplicationProvider.getApplicationContext(), "test")

    /** What a player would publish. Robolectric's controller reads these from its shadow. */
    private val controller = session.controller.also { shadowOf(it).setPackageName("com.player") }

    @After
    fun release() = session.release()

    private fun report(read: TrackInfo? = null): String = formatSessionReports(
        listOf("Melisma" to "test"),
        listOf(controller.report("Player", "FOLLOWED", read, nowElapsed = 10_000)),
    )

    @Test
    fun `every metadata key is listed with its value, whatever its type`() {
        shadowOf(controller).setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Lemon")
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "abc123")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 254_000)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888))
                .putRating(MediaMetadata.METADATA_KEY_USER_RATING, Rating.newHeartRating(true))
                .putString("com.player.isrc", "JPU901800001")
                .build(),
        )
        val text = report()

        assertTrue(text, "android.media.metadata.TITLE = \"Lemon\"" in text)
        assertTrue(text, "android.media.metadata.MEDIA_ID = \"abc123\"" in text)
        assertTrue(text, "android.media.metadata.DURATION = 254000" in text)
        assertTrue(text, "android.media.metadata.ALBUM_ART = bitmap 4×3" in text)
        assertTrue(text, "android.media.metadata.USER_RATING = Rating:" in text)
        // A key no standard names is exactly what this is for.
        assertTrue(text, "com.player.isrc = \"JPU901800001\"" in text)
    }

    @Test
    fun `playback state, extras and what Melisma reads are all shown`() {
        shadowOf(controller).setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 42_000, 1f, 9_000)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO)
                .setActiveQueueItemId(7)
                .addCustomAction("like", "Like", android.R.drawable.star_on)
                .setExtras(Bundle().apply { putString("source", "radio") })
                .build(),
        )
        shadowOf(controller).setExtras(
            Bundle().apply {
                putInt("count", 3)
                putStringArray("tags", arrayOf("a", "b"))
                putBundle("nested", Bundle().apply { putBoolean("on", true) })
                putString("missing", null)
            },
        )
        val text = report(
            TrackInfo(title = "Lemon", artist = "Kenshi Yonezu", album = "", durationMs = 254_000, mediaId = "spotify:track:0000000000000000000000"),
        )

        assertTrue(text, "state = PLAYING" in text)
        assertTrue(text, "position = 42000 ms, reported 1000 ms ago" in text)
        assertTrue(text, "actions = SEEK_TO PLAY_PAUSE" in text)
        assertTrue(text, "activeQueueItemId = 7" in text)
        assertTrue(text, "customActions = like" in text)
        assertTrue(text, "extras.source = \"radio\"" in text)
        assertTrue(text, "count = 3" in text)
        assertTrue(text, "tags = [\"a\", \"b\"]" in text)
        assertTrue(text, "nested = {on=true}" in text)
        assertTrue(text, "missing = null" in text)
        assertTrue(text, "spotifyTrackId = 0000000000000000000000" in text)
        assertTrue(text, "cacheKey = \"sp:0000000000000000000000\"" in text)
    }

    @Test
    fun `a state without an update time shows the position alone`() {
        shadowOf(controller).setPlaybackState(
            PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 5_000, 0f, 0).build(),
        )
        val text = report()
        assertTrue(text, "state = PAUSED" in text)
        assertTrue(text, "position = 5000 ms\n" in text)
        assertTrue(text, "actions = none" in text)
    }

    private fun queue(size: Int, describe: MediaDescription.Builder.(Int) -> Unit = {}) = List(size) { index ->
        MediaSession.QueueItem(MediaDescription.Builder().apply { describe(index) }.build(), index.toLong())
    }

    @Test
    fun `the queue is shown around the playing item`() {
        val lines = queueLines(
            queue(10) { index ->
                setMediaId("id$index")
                setTitle("Song $index")
                setSubtitle("Artist")
                if (index == 4) setExtras(Bundle().apply { putLong("durationMs", 1_000) })
            },
            "Mix",
            activeId = 4,
        ).toMap()

        assertEquals("\"Mix\"", lines["title"])
        assertEquals("10", lines["size"])
        assertEquals(
            "mediaId=\"id4\", title=\"Song 4\", subtitle=\"Artist\", durationMs=1000",
            lines["#4 (playing)"],
        )
        // One before the playing item and the few after it, not the whole album.
        assertEquals(listOf("#3", "#4 (playing)", "#5", "#6", "#7"), lines.keys.filter { it.startsWith("#") })
    }

    @Test
    fun `a queue with nothing marked playing shows its start`() {
        val lines = queueLines(queue(7), queueTitle = null, activeId = null).toMap()
        assertEquals(listOf("size", "#0", "#1", "#2", "#3", "#4"), lines.keys.toList())
        assertEquals("mediaId=none, title=\"\", subtitle=\"\"", lines["#0"])
        assertTrue(queueLines(null, null, null).isEmpty())
    }

    @Test
    fun `a session that publishes nothing has empty sections`() {
        val text = report()
        assertTrue(text, "-- What Melisma reads\n  (none)" in text)
        assertTrue(text, "-- Metadata\n  (none)" in text)
        assertTrue(text, "-- Queue\n  (none)" in text)
    }

    /** Stands in for a player's own class: no `CREATOR`, so it cannot be read back. */
    private class Foreign : Parcelable {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    @Test
    fun `a bundle holding a class this app cannot read says so instead of failing`() {
        val parcel = Parcel.obtain()
        try {
            Bundle().apply { putParcelable("foreign", Foreign()) }.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val arrived = Bundle.CREATOR.createFromParcel(parcel)
            assertEquals(listOf("unreadable" to "BadParcelableException"), bundleLines(arrived))
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `the report starts with the header and numbers each session`() {
        val text = formatSessionReports(
            listOf("Melisma" to "2.7.2", "Device" to "Pixel"),
            listOf(
                SessionReport("a.b", "A", "FOLLOWED", emptyList()),
                SessionReport("c.d", "C", "Not followed", listOf("Queue" to listOf("size" to "0"))),
            ),
        )
        assertEquals(
            "Melisma: 2.7.2\nDevice: Pixel\n\n== 1. A (a.b)\nFOLLOWED\n\n== 2. C (c.d)\nNot followed\n" +
                "-- Queue\n  size = 0\n",
            text,
        )
        assertEquals("Melisma: x\n\nNo media sessions.\n", formatSessionReports(listOf("Melisma" to "x"), emptyList()))
    }

    @Test
    fun `every action flag is named, and a bit this build does not know is kept as a number`() {
        assertEquals("none", actionNames(0))
        assertEquals(
            "PREPARE_FROM_MEDIA_ID PREPARE_FROM_SEARCH PREPARE_FROM_URI",
            actionNames(
                PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID or PlaybackState.ACTION_PREPARE_FROM_SEARCH or
                    PlaybackState.ACTION_PREPARE_FROM_URI,
            ),
        )
        assertEquals("PLAY 0x100000000", actionNames(PlaybackState.ACTION_PLAY or (1L shl 32)))
    }

    @Test
    fun `the status names the rule that passed a session over`() {
        fun status(
            own: Boolean = false,
            followed: Boolean = false,
            ignored: Boolean = false,
            hasTitle: Boolean = true,
            playing: Boolean = false,
            ago: Long? = null,
        ) = sessionStatus(own, followed, ignored, hasTitle, playing, ago)

        assertEquals("Melisma's own session, never followed; its app not seen playing", status(own = true, followed = true))
        assertEquals("FOLLOWED by the lyrics; its app last reported playing 2 s ago", status(followed = true, ago = 2_500))
        assertTrue(status(ignored = true).startsWith("Not followed: switched off under Players to follow"))
        assertTrue(status(hasTitle = false).startsWith("Not followed: publishes no title"))
        assertTrue(status(playing = true).startsWith("Not followed: another playing session"))
        assertTrue(status().startsWith("Not followed: not playing"))
    }
}
