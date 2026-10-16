package app.myspoti.spotify.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Where the Web Player page runs: an Android WebView or desktop Chrome over CDP.
 *
 * A host loads open.spotify.com with the user's session, injects [BridgeScript] after each
 * page load, blocks ad audio ([AdBlock.blockedHosts]) and relays the page's messages.
 */
interface BridgeHost {
    /** Runs [script] in the page. Fire and forget: answers come back through [messages]. */
    suspend fun evaluate(script: String)

    /** JSON strings posted by the bridge (`MyspotiHost.post` / `__mspHostPost`). */
    val messages: Flow<String>
}

/** The bridge's source, bundled as a resource of this module. */
object BridgeScript {
    val source: String by lazy {
        BridgeScript::class.java.getResourceAsStream("/bridge/player-bridge.js")!!
            .bufferedReader().use { it.readText() }
    }
}

/**
 * Audio ads. Spotify's server puts them in the player's queue (track-playback state machine),
 * each with an audio file in a format of its own. The player registers itself without those
 * formats ([withoutAdFormats]), so an ad comes with nothing to play; the ad audio host is
 * refused as well. The player then halts at the end of the song before the ad, and
 * [app.myspoti.spotify.SpotifyPlayer] starts the next song.
 */
object AdBlock {
    val blockedHosts = listOf("adstudio-assets.scdn.co")

    /** Formats only ads use: the ad mp3 links and the ad manifests. Songs and episodes use others. */
    val adFormats = setOf("manifest_urls_audio_ad", "file_urls_mp3")

    fun isBlocked(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        return blockedHosts.any { host == it || host.endsWith(".$it") }
    }

    /** The player's registration (POST track-playback/v1/devices), to rewrite with [withoutAdFormats]. */
    fun isDeviceRegistration(method: String, url: String): Boolean =
        method.equals("POST", ignoreCase = true) && url.substringBefore('?').endsWith("/track-playback/v1/devices")

    /** [body] (the registration JSON) without [adFormats] in its manifest formats; unchanged when unreadable. */
    fun withoutAdFormats(body: String): String = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val device = root["device"]?.jsonObject ?: return body
        val caps = device["capabilities"]?.jsonObject ?: return body
        val formats = caps["manifest_formats"]?.jsonArray ?: return body
        val kept = JsonArray(formats.filter { (it as? JsonPrimitive)?.contentOrNull !in adFormats })
        JsonObject(root + ("device" to JsonObject(device + ("capabilities" to JsonObject(caps + ("manifest_formats" to kept)))))).toString()
    }.getOrDefault(body)
}
