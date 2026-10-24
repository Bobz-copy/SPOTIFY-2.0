package app.myspoti.spotify.bridge

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Which way the host sends the page's relayed requests (see the host's ControlRelay).
 *
 * Some free accounts get every relayed command refused (402 COMMAND_FAILED) from the first one,
 * while the same phone plays in a browser. One lead (2026-10-23 reports): the WebView reaches
 * Spotify over one address family and the host's own client over the other (IPv6 against IPv4,
 * 464XLAT), so Spotify sees the commands come from another place than the player — a remote
 * control, which a free account may not use. A refused command is sent once more pinned to the
 * other family, then through the platform's own HTTP stack; whichever is taken is kept for the
 * rest of the session, for every relayed request.
 */
enum class RelayRoute { DEFAULT, IPV4, IPV6, HTTP_ENGINE }

enum class IpFamily {
    V4, V6;

    companion object {
        fun of(address: InetAddress?): IpFamily? = when (address) {
            is Inet6Address -> V6
            is Inet4Address -> V4
            else -> null
        }
    }
}

class RelayRoutes(private val engineAvailable: Boolean) {
    /** The route every relayed request takes: [RelayRoute.DEFAULT] until another was taken after a refusal. */
    @Volatile var kept: RelayRoute = RelayRoute.DEFAULT
        private set

    /**
     * The route to try after a refusal on every route in [tried] (the first one is [kept]).
     * [used]: the family the refused request went out on (null: not known), so the retry takes
     * the other one. Null: nothing left, the refusal stands.
     */
    fun next(tried: List<RelayRoute>, used: IpFamily?): RelayRoute? {
        val other = when {
            used == IpFamily.V6 -> RelayRoute.IPV4
            used == IpFamily.V4 -> RelayRoute.IPV6
            tried.lastOrNull() == RelayRoute.IPV4 -> RelayRoute.IPV6
            else -> RelayRoute.IPV4
        }
        // The other family is tried once: a retry pinned to it that was refused too leaves the
        // platform stack only.
        val familyRetried = tried.drop(1).any { it == RelayRoute.IPV4 || it == RelayRoute.IPV6 }
        val order = listOfNotNull(other.takeIf { !familyRetried }, RelayRoute.HTTP_ENGINE.takeIf { engineAvailable })
        return order.firstOrNull { it !in tried }
    }

    /** [route] was answered after the routes before it were refused: kept for the session. */
    fun won(route: RelayRoute) {
        kept = route
    }

    companion object {
        /** The addresses of [family] only; none is an unknown host (the network lacks that family). */
        fun only(family: IpFamily, host: String, all: List<InetAddress>): List<InetAddress> =
            all.filter { IpFamily.of(it) == family }.ifEmpty { throw UnknownHostException("$host has no ${family.name} address") }

        /**
         * An address for the logs: its family and the first part only, never the whole of it
         * ("v6 2a01:…", "v4 85.x.x.x"): enough to tell two paths apart, not to locate anyone.
         */
        fun mask(address: InetAddress?): String = when (address) {
            is Inet6Address -> "v6 " + (address.hostAddress ?: "").substringBefore(':') + ":…"
            is Inet4Address -> "v4 " + (address.hostAddress ?: "").substringBefore('.') + ".x.x.x"
            else -> "?"
        }
    }
}
