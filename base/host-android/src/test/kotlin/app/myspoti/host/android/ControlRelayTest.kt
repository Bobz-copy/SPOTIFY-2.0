package app.myspoti.host.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRelayTest {

    @Test fun `playback commands and states leave from the app`() {
        assertTrue(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/connect-state/v1/player/command/from/a/to/b"))
        assertTrue(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/track-playback/v1/devices/a/state"))
    }

    // What reports a listen (the milliseconds really played) must not leave with the package name.
    @Test fun `play telemetry leaves from the app`() {
        assertTrue(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/melody/v1/msg/batch"))
        assertTrue(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/gabo-receiver-service/v3/events"))
        assertTrue(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/gabo-receiver-service/public/v3/events"))
    }

    @Test fun `sign-in, audio and pages stay with the WebView`() {
        assertFalse(ControlRelay.isRelayed("https://accounts.spotify.com/melody/v1/x"))
        assertFalse(ControlRelay.isRelayed("https://audio-fa.scdn.co/audio/abc"))
        assertFalse(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/widevine-license/v1/audio/license"))
    }
}
