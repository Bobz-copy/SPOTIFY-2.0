package app.myspoti.spotify.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdBlockTest {
    @Test
    fun `blocks the ad audio host only`() {
        assertTrue(AdBlock.isBlocked("https://adstudio-assets.scdn.co/mp3/d0448345e619692e0b13626ad4994d4b824f7107.mp3"))
        assertFalse(AdBlock.isBlocked("https://audio-fa.scdn.co/audio/abc"))
        assertFalse(AdBlock.isBlocked("https://i.scdn.co/image/ab67616d0000b273"))
        assertFalse(AdBlock.isBlocked("https://evil.com/adstudio-assets.scdn.co/x.mp3"))
    }

    @Test
    fun `base62 ids convert to metadata gids`() {
        // Pair checked against a captured metadata/4 request.
        assertTrue(app.myspoti.spotify.base62ToHex("55vPnuXfuzXNW75JpuSczF") == "a734c1d4e4d84c4093c32d5bce9797a3")
    }

    @Test
    fun `bridge script is bundled`() {
        assertTrue(BridgeScript.source.contains("window.Myspoti"))
    }
}
