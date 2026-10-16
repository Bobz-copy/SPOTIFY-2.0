package com.music.spotui.lossless

/*
 * Ported and extended from Spotui (https://github.com/Spotui/Spotui), GPL-3.0.
 * Spotui gated Tidal only; Qobuz and Amazon use the same SPOTIFLAC-HMAC-V1
 * signing against their own community hosts.
 */

import android.content.Context
import android.util.Base64
import android.util.Log
import com.music.spotui.data.preferences.getSpotiflacSession
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SpotiFLAC's **gated** community download — SpotiFLAC's own, most reliable FLAC
 * source. Uses the signed session captured by the verification screen to HMAC-sign
 * a request to the community `/api/dl` endpoint.
 *
 * The `/api/dl` hosts are signature-gated (not Cloudflare-gated), so this runs
 * natively — no WebView. Signing scheme `SPOTIFLAC-HMAC-V1` is ported exactly
 * from SpotiFLAC's client.
 */
internal object SpotiflacGated {

    private const val TAG = "SpotiflacGated"
    const val APP_VERSION = "4.8.5"
    const val PLATFORM = "android"
    private const val SCHEME = "SPOTIFLAC-HMAC-V1"
    private const val WINDOW_SECONDS = 300
    private const val USER_AGENT = "SpotiFLAC-Mobile/$APP_VERSION"

    /** Whether a session exists — cheap, no network. */
    fun hasSession(context: Context): Boolean = getSpotiflacSession(context) != null

    /**
     * Signed `/api/dl` for a provider track id → the audio payload reference.
     * Returns a [GatedHit]: a direct FLAC url, or a DASH `<MPD>` manifest base64
     * (single-file FLAC is returned as `url`; hi-res as `dashManifestBase64`).
     */
    data class GatedHit(val url: String, val dashBase64: String, val quality: Int)

    fun flacUrl(context: Context, provider: String, providerId: String, quality: String): GatedHit? {
        val session = getSpotiflacSession(context) ?: return null
        val community = LosslessRegistry.communityDlUrl(provider) ?: return null
        return runCatching {
            val payload = JSONObject().put("id", providerId).put("quality", quality).toString()
            val bodyBytes = payload.toByteArray(Charsets.UTF_8)
            val path = URL(community).path
            val headers = signHeaders(session.sessionId, session.sessionSecret, "POST", path, bodyBytes)
            val body = post(community, bodyBytes, headers)
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: root
            // Either a plain url, or base64 manifest (BTS json single-file, or DASH MPD).
            val direct = data.optString("url").takeIf { it.startsWith("http") }
            val manifestB64 = data.optString("manifest").takeIf { m -> m.isNotBlank() }
                ?: data.optString("resource").takeIf { m -> m.startsWith("MANIFEST:") }
                    ?.substringAfter("MANIFEST:")?.trim()
            when {
                direct != null && direct.contains(".flac", true) -> GatedHit(direct, "", 16)
                direct != null -> GatedHit(direct, "", 0)
                manifestB64 != null -> {
                    val decoded = String(Base64.decode(manifestB64, Base64.DEFAULT), Charsets.UTF_8)
                    val manifest = runCatching { JSONObject(decoded) }.getOrNull()
                    val urls = manifest?.optJSONArray("urls")
                    // Multi-segment BTS manifest without playable single file → not usable here;
                    // a DASH <MPD> text is usable through DashFlacRemux.
                    when {
                        urls == null && decoded.contains("<MPD") -> GatedHit("", manifestB64, 24)
                        urls != null && urls.length() == 1 &&
                            (manifest.optString("mimeType").contains("flac", true) ||
                                urls.optString(0).contains(".flac", true)) ->
                            GatedHit(urls.optString(0), "", 16)
                        // A multi-segment JSON manifest is not a DASH <MPD>: the remux cannot read it.
                        else -> return@runCatching null
                    }
                }
                else -> return@runCatching null
            }
        }.onFailure { Log.d(TAG, "gated miss: ${it.message}") }.getOrNull()
    }

    // ── SPOTIFLAC-HMAC-V1 signing (exact port) ────────────────────────────────
    private fun signHeaders(
        sessionId: String,
        sessionSecret: String,
        method: String,
        path: String,
        body: ByteArray,
    ): Map<String, String> {
        val ts = iso8601Millis()
        val nonce = randomHex(12)
        val bodyHash = sha256Hex(body)
        val window = (System.currentTimeMillis() / 1000L) / WINDOW_SECONDS
        // rolling key = HMAC(session_secret, "{window}:{session_id}") → base64url no-pad
        val rk = hmacSha256(sessionSecret.toByteArray(Charsets.UTF_8), "$window:$sessionId".toByteArray(Charsets.UTF_8))
        val rkString = b64UrlNoPad(rk)
        val signingInput =
            "$SCHEME\n$method\n$path\n\n$bodyHash\n$ts\n$nonce\n$sessionId\n$APP_VERSION\n$PLATFORM"
        val sig = b64UrlNoPad(hmacSha256(rkString.toByteArray(Charsets.UTF_8), signingInput.toByteArray(Charsets.UTF_8)))
        return mapOf(
            "X-Sig-Session" to sessionId,
            "X-Sig-Timestamp" to ts,
            "X-Sig-Nonce" to nonce,
            "X-Sig-Body-Sha256" to bodyHash,
            "X-Sig-Signature" to sig,
            "X-Sig-App-Version" to APP_VERSION,
            "X-Sig-Platform" to PLATFORM,
        )
    }

    private fun post(urlString: String, body: ByteArray, headers: Map<String, String>): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${text.take(160)}")
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun b64UrlNoPad(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun iso8601Millis(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(System.currentTimeMillis()))

    private fun randomHex(nBytes: Int): String {
        val b = ByteArray(nBytes)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
