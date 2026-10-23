package app.myspoti.host.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRelayTest {

    @Test fun `playback commands and states leave from the app`() {
        assertTrue(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/connect-state/v1/player/command/from/a/to/b"))
        assertTrue(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/track-playback/v1/devices/a/state"))
    }

    // Relayed in 2.0.2-beta.1, whose page then never registered its device: back with the WebView.
    @Test fun `play telemetry stays with the WebView, as in 2_0_1`() {
        assertFalse(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/melody/v1/msg/batch"))
        assertFalse(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/melody/v1/time"))
        assertFalse(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/gabo-receiver-service/v3/events"))
        assertFalse(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/gabo-receiver-service/public/v3/events"))
    }

    @Test fun `sign-in, audio and pages stay with the WebView`() {
        assertFalse(ControlRelay.isRelayed("https://accounts.spotify.com/melody/v1/x"))
        assertFalse(ControlRelay.isRelayed("https://audio-fa.scdn.co/audio/abc"))
        assertFalse(ControlRelay.isRelayed("https://gew4-spclient.spotify.com/widevine-license/v1/audio/license"))
    }

    // 402 diagnostics: what a refused session's report carries, never an id, a token or a name.
    @Test fun `the registration answer keeps its plain fields and hides ids`() {
        val s = ControlRelay.registrationSummary(
            """{"initial_seq_num":12345,"effective_license":"tft","supports_observing":true,"device_keep_alive_update_seconds":21600,"file_format_filter":["mp3"],"registration_token":"secret"}""",
        )
        assertTrue(s, s.contains("effective_license=tft"))
        assertTrue(s, s.contains("supports_observing=true"))
        assertTrue(s, s.contains("file_format_filter=[mp3]"))
        assertTrue(s, s.contains("registration_token") && !s.contains("secret"))
        assertFalse(s, s.contains("12345"))
    }

    @Test fun `the cluster names this player and the active one without ids`() {
        val self = "a".repeat(40)
        val other = "b".repeat(40)
        val s = ControlRelay.clusterSummary(
            """{"active_device_id":"$other","devices":{
              "$self":{"device_type":"COMPUTER","license":"tft","can_play":true,"name":"Pixel","capabilities":{"is_controllable":true,"can_be_player":true}},
              "$other":{"device_type":"SMARTPHONE","license":"mft","can_play":true,"name":"Daniel","public_ip":"1.2.3.4","device_software_version":"Spotify 9.0.1"}},
              "player_state":{"restrictions":{"disallow_resuming_reasons":["not_paused"],"disallow_seeking_reasons":[]}}}""",
            self,
        )!!
        assertTrue(s, s.startsWith("devices=2 active=other self_listed=true [self COMPUTER license=tft can_play=true controllable=true player=true; active SMARTPHONE license=mft"))
        assertTrue(s, s.contains("Spotify 9.0.1"))
        assertTrue(s, s.endsWith("restrictions: resuming=not_paused"))
        assertFalse(s, s.contains(self) || s.contains(other) || s.contains("Daniel") || s.contains("Pixel") || s.contains("1.2.3.4"))
    }

    @Test fun `a body that is not a cluster says nothing`() {
        assertNull(ControlRelay.clusterSummary("""{"ack_id":"x"}""", null))
        assertNull(ControlRelay.clusterSummary("not json", null))
    }

    @Test fun `header names only, sorted`() {
        val h = org.json.JSONObject("""{"Authorization":"Bearer x","client-token":"y","Content-Type":"application/json"}""")
        assertEquals("authorization,client-token,content-type", ControlRelay.headerNames(h))
    }
}
