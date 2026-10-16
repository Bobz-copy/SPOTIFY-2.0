package app.myspoti.spotify.bridge

import kotlinx.coroutines.flow.Flow

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

/** Requests every host refuses. Blocking the ad audio makes the player skip the ad itself. */
object AdBlock {
    val blockedHosts = listOf("adstudio-assets.scdn.co")

    fun isBlocked(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        return blockedHosts.any { host == it || host.endsWith(".$it") }
    }
}
