package com.music.spotui.lossless

/*
 * The signed session SpotiFLAC Mobile's download extensions use (qobuz-web,
 * tidal-web…), written from its protocol: the extension manifest's `signedSession`
 * block and the app's signed_session runtime (bootstrap → hosted challenge →
 * grant → exchange; ZARZ-HMAC-V1 signatures; download tickets).
 * SpotiFLAC Mobile is MIT (Copyright (c) 2026 zarzet), its extensions Apache-2.0:
 * see THIRD_PARTY_NOTICES.md.
 */

import android.content.Context
import com.music.spotui.data.preferences.getSpotiflacSession
import com.music.spotui.data.preferences.saveSpotiflacSession
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object ZarzSession {
    const val BASE = "https://api.zarz.moe/v2"
    const val PLATFORM = "extension"

    /** The extension a session is issued to; the server checks it in every signature. */
    enum class Ext(val appVersion: String) {
        TIDAL("tidal-web@1.2.5"),
        QOBUZ("qobuz-web@1.2.21"),
    }
    const val CALLBACK = "spotiflac://session-grant"
    private const val SCHEME = "ZARZ-HMAC-V1"
    private const val PREFIX = "X-Zarz-"
    private const val WINDOW_SECONDS = 300L
    private const val USER_AGENT = "SpotiFLAC-Mobile/5.1.1"

    /** The grant redirects the verification page sends back to the app (spotiflac://session-grant…). */
    val grants = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** A challenge page to open (the user solves it), or null when a session came back directly. */
    class Bootstrap(val challengeUrl: String?, val state: String)

    /** The gateway wants the user to verify again (HTTP 428 / VERIFY_REQUIRED). */
    class VerifyRequired : Exception("VERIFY_REQUIRED")

    fun installId(context: Context): String =
        com.music.spotui.data.preferences.spotiflacInstallId(context).takeIf { it.isNotBlank() } ?: randomHex(16)

    /** GET /bootstrap: a session right away, or the challenge to solve. */
    fun bootstrap(context: Context, ext: Ext, installId: String): Bootstrap {
        val (code, text) = request("GET", "$BASE/bootstrap?app_version=${enc(ext.appVersion)}&install_id=${enc(installId)}", null, emptyMap())
        if (code !in 200..299) throw java.io.IOException("bootstrap HTTP $code: ${text.take(120)}")
        val o = JSONObject(text)
        if (store(context, ext, installId, o)) return Bootstrap(null, "")
        val state = randomHex(16)
        val given = o.optString("auth_url").ifBlank { o.optString("challenge_url") }
        val callback = "$CALLBACK?cb_version=v2grant&state=$state"
        val url = when {
            given.startsWith("https://") -> if ("state=" in given) given else "$given${if ('?' in given) '&' else '?'}state=$state"
            o.optString("challenge_id").isNotBlank() -> "$BASE/challenge?id=${enc(o.optString("challenge_id"))}&cb=${enc(callback)}"
            else -> throw java.io.IOException("bootstrap gave neither a session nor a challenge")
        }
        return Bootstrap(url, state)
    }

    /** POST /session/exchange with the grant the challenge page handed back. */
    fun exchange(context: Context, ext: Ext, installId: String, grant: String) {
        val body = JSONObject().put("grant", grant).put("install_id", installId)
            .put("app_version", ext.appVersion).put("platform", PLATFORM).toString()
        val (code, text) = request("POST", "$BASE/session/exchange", body, mapOf("Content-Type" to "application/json"))
        if (code !in 200..299 || !store(context, ext, installId, JSONObject(text))) throw java.io.IOException("session exchange failed: HTTP $code")
    }

    /**
     * A signed JSON call ([path] under [BASE], e.g. "/tickets"); returns the body.
     * Refreshes a session that expires within the hour first.
     */
    fun signedJson(context: Context, ext: Ext, method: String, path: String, body: JSONObject?, extra: Map<String, String> = emptyMap()): JSONObject {
        refreshIfDue(context, ext)
        val (code, text) = signed(context, ext, method, path, body?.toString(), extra)
        val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (code == 428 || o.optString("code").equals("VERIFY_REQUIRED", true) || o.optBoolean("needsVerification")) throw VerifyRequired()
        if (code == 401 && o.optString("code").equals("SESSION_INVALID", true)) {
            com.music.spotui.data.preferences.clearSpotiflacSession(context, ext.appVersion)
            throw VerifyRequired()
        }
        if (code !in 200..299) throw java.io.IOException("HTTP $code: ${(o.optString("error").ifBlank { text }).take(160)}")
        return o
    }

    /** A download ticket for one resource ("qbz", "track", the track's URL). */
    fun ticket(context: Context, ext: Ext, provider: String, type: String, id: String): String {
        val hash = sha256Hex("$provider:$type:${id.lowercase()}".toByteArray())
        val o = signedJson(context, ext, "POST", "/tickets", JSONObject()
            .put("capability", "download_ticket").put("provider", provider).put("resource_hash", hash))
        return o.optString("ticket_id").ifBlank { o.optString("ticket") }.trim().ifBlank { throw java.io.IOException("no ticket_id") }
    }

    private fun refreshIfDue(context: Context, ext: Ext) {
        val s = getSpotiflacSession(context, ext.appVersion) ?: throw VerifyRequired()
        val expires = parseIso(s.expiresAt) ?: return
        val left = expires - System.currentTimeMillis()
        if (left <= 0) throw VerifyRequired()
        if (left > 3_600_000) return
        runCatching {
            val (code, text) = signed(context, ext, "POST", "/session/refresh", JSONObject().put("install_id", s.installId).toString(), emptyMap())
            if (code in 200..299) store(context, ext, s.installId, JSONObject(text), keepMissing = true)
        }
    }

    private fun signed(context: Context, ext: Ext, method: String, path: String, body: String?, extra: Map<String, String>): Pair<Int, String> {
        val s = getSpotiflacSession(context, ext.appVersion) ?: throw VerifyRequired()
        val url = BASE + path
        val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
        val headers = signHeaders(s.sessionId, s.sessionSecret, ext.appVersion, method, URL(url).path, bytes, System.currentTimeMillis(), randomHex(12)) +
            extra + (if (body != null) mapOf("Content-Type" to "application/json") else emptyMap())
        return request(method, url, body, headers)
    }

    /** ZARZ-HMAC-V1: a key rolled every 5 minutes from the secret, then the request signed with it. */
    internal fun signHeaders(sessionId: String, secret: String, appVersion: String, method: String, path: String, body: ByteArray, nowMs: Long, nonce: String): Map<String, String> {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(nowMs))
        val hash = sha256Hex(body)
        val window = (nowMs / 1000) / WINDOW_SECONDS
        val rolling = hmac(secret.toByteArray(), "$window:$sessionId".toByteArray())
        val signing = listOf(SCHEME, method, path, "", hash, ts, nonce, sessionId, appVersion, PLATFORM).joinToString("\n")
        val signature = hmac(rolling.toByteArray(), signing.toByteArray())
        return linkedMapOf(
            "Session" to sessionId, "Timestamp" to ts, "Nonce" to nonce, "Body-SHA256" to hash,
            "Signature" to signature, "App-Version" to appVersion, "Platform" to PLATFORM,
        ).mapKeys { PREFIX + it.key }
    }

    private fun store(context: Context, ext: Ext, installId: String, o: JSONObject, keepMissing: Boolean = false): Boolean {
        val id = o.optString("session_id"); val secret = o.optString("session_secret"); val exp = o.optString("expires_at")
        if (id.isBlank() || secret.isBlank()) {
            if (!keepMissing || exp.isBlank()) return false
            val old = getSpotiflacSession(context, ext.appVersion) ?: return false
            saveSpotiflacSession(context, old.sessionId, old.sessionSecret, installId, exp, ext.appVersion)
            return true
        }
        saveSpotiflacSession(context, id, secret, installId, exp, ext.appVersion)
        return true
    }

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            // The app's own form, "SpotiFLAC-Mobile/<version>": the bare name is refused (403).
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) doOutput = true
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseIso(value: String): Long? = runCatching {
        java.time.OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli()
    }.getOrNull()

    private fun hmac(key: ByteArray, data: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data),
    )

    internal fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun randomHex(n: Int): String = ByteArray(n).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
