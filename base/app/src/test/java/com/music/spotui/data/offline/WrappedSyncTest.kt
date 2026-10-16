package com.music.spotui.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The honest-reporting rules: real listens only, same context as the play. */
class WrappedSyncTest {

    private fun listen(context: String = "") = WrappedSync.Listen(
        trackId = "3URIq3iShZ0nBF3gcCwqHy", albumId = "4aeweJs3H2T3YfTzzzW2qL",
        startedAt = 1, playedMs = 45_000, durationMs = 200_000, threshold = true, context = context,
    )

    @Test fun deferredListensReplayOnTheContextTheyStartedFrom() {
        assertEquals("spotify:playlist:abc123", WrappedSync.contextFor(listen("spotify:playlist:abc123")))
        // No recorded context (the Downloaded list): the track's album.
        assertEquals("spotify:album:4aeweJs3H2T3YfTzzzW2qL", WrappedSync.contextFor(listen()))
    }

    @Test fun albumFallbackNeverProducesAUriWithoutAnId() {
        val noAlbum = WrappedSync.Listen("0K4Ym9aQqD06mG6rJ0SfWZ", "", 1, 40_000, 180_000, true, "")
        assertEquals("spotify:track:0K4Ym9aQqD06mG6rJ0SfWZ", WrappedSync.contextFor(noAlbum))
    }

    @Test fun thePlayerIdsAreFullyAllocated() {
        assertEquals(32, WrappedSync.randomHex(16).length)
        assertTrue(WrappedSync.randomB64(21).matches(Regex("[A-Za-z0-9_-]{28}")))
    }

    @Test fun theCountableThresholdIsSpotifyS() {
        assertEquals(30_000L, WrappedSync.THRESHOLD_MS)
        // sanity: a 29 s listen does not cross it.
        assertFalse(29_000L >= WrappedSync.THRESHOLD_MS)
        assertTrue(30_500L >= WrappedSync.THRESHOLD_MS)
    }
}
