package app.myspoti.host.android

/**
 * The playback credentials the app's own requests need, captured from the Web
 * Player's own traffic as [ControlRelay] relays it: the device id, its cluster
 * host, and the page's auth tokens. Nothing is guessed — every value was sent
 * by the page itself.
 */
object RelayCredentials {

    /** The Web Player's Spotify device id (bare hex, ~40 chars). */
    @Volatile var deviceId: String = ""

    /** The cluster host the player talks to, e.g. "gew4-spclient.spotify.com". */
    @Volatile var clusterHost: String = ""

    /** The page's Bearer token (rotates; refreshed whenever the player calls out). */
    @Volatile var authorization: String = ""

    /** The page's client token (long lived, per app instance). */
    @Volatile var clientToken: String = ""

    /** Last time any of these were seen and used successfully by the player. */
    @Volatile var seenAt: Long = 0

    /** All of it or nothing: a play report needs every piece. */
    fun ready(): Boolean = deviceId.isNotBlank() && clusterHost.isNotBlank() &&
        authorization.isNotBlank() && clientToken.isNotBlank()
}
