package com.music.spotui.lossless

/*
 * Ported from Spotui (https://github.com/Spotui/Spotui), GPL-3.0, and adapted:
 * Odesli is gone (public API closed), so ids come from the providers' own
 * backends (search by title+artist, ISRC matched) and Qobuz's public ISRC search.
 */

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Resolves a Spotify track to lossless FLAC via SpotiFLAC — its gated backend
 * first (the user's signed session), then the public community backends. Each
 * provider fails gracefully: a down or throttled source just falls through.
 *
 * Priority (most reliable first):
 *   1. SpotiFLAC gated session — Qobuz (found by ISRC in its own catalogue), then
 *      Tidal (16 or 24-bit).
 *   2. Tidal community backends — `/track/?id=`, single-file FLAC (16) or a
 *      DASH manifest to remux into one FLAC file (24).
 *   3. Amazon self-resolve by Spotify id (often dormant).
 *
 * The same recording only: ISRC first (exact), else title+artist normalised with
 * a duration within 3 s — a live take, a remix or a cover is never taken.
 */
object LosslessSource {

    private const val TAG = "LosslessSource"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Lost audio types the resolver hands back. Either [url] or [dashBase64] is set. */
    data class LosslessFile(
        val url: String,
        val dashBase64: String,
        val provider: String,
        /** Bit depth the source claims (the STREAMINFO after a remux tells the truth). */
        val quality: Int,
    )

    sealed interface Result
    data class Success(val track: LosslessFile, val isrcMatched: Boolean) : Result
    object NotFound : Result
    data class Error(val message: String) : Result

    /** Track details from the play site; the downloader fills them from Spotify. */
    data class TrackInfo(val title: String = "", val artist: String = "", val durationSec: Int = 0, val isrc: String = "")

    /** Backends sometimes serve 30 s previews instead of the song: anything under this is one. */
    const val MIN_FULL_TRACK_SEC = 35

    suspend fun resolve(
        context: Context,
        spotifyId: String,
        info: TrackInfo = TrackInfo(),
        preferHiRes: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        // Qobuz's own catalogue search finds the ISRC quickly and does not depend on the
        // community backends; the Tidal id does (their search), and they are often down.
        val qobuz = qobuzIdForIsrc(info.isrc)
        val session = SpotiflacGated.hasSession(context)

        // 1. SpotiFLAC's gated /api/dl (needs the user's verification session): Qobuz first
        //    when the ISRC found it, so a dead Tidal backend costs no time.
        if (session && qobuz != null) gated(context, null, qobuz, info, preferHiRes)?.let { return@withContext it }
        val tidal = tidalHit(info)
        if (session && tidal != null) gated(context, tidal, null, info, preferHiRes)?.let { return@withContext it }

        // 2. Tidal community backends (no account needed).
        if (tidal != null) tidalDirect(tidal, info, preferHiRes)?.let { return@withContext it }

        // 3. Amazon (self-resolves from the Spotify id; usually dormant).
        amazon(spotifyId)?.let { return@withContext it }

        NotFound
    }

    /** Gated providers: Tidal first (its match is the strongest), then Qobuz by ISRC. */
    private suspend fun gated(
        context: Context,
        tidal: TidalHit?,
        qobuzId: String?,
        info: TrackInfo,
        preferHiRes: Boolean,
    ): Result? {
        if (tidal != null) {
            val qualities = if (preferHiRes) listOf("HI_RES_LOSSLESS", "LOSSLESS") else listOf("LOSSLESS")
            for (q in qualities) {
                val hit = runCatching { SpotiflacGated.flacUrl(context, "tidal", tidal.id, q) }
                    .getOrElse { Log.w(TAG, "gated tidal $q: ${it.message}"); null } ?: continue
                val result = hitAsSuccess(hit, "SpotiFLAC tidal", info, isrcMatches(tidal.isrc, info.isrc))
                if (result is Success) return result
            }
        }
        if (qobuzId != null) {
            val qualities = if (preferHiRes) listOf("HI_RES_LOSSLESS", "LOSSLESS") else listOf("LOSSLESS")
            for (q in qualities) {
                val hit = runCatching { SpotiflacGated.flacUrl(context, "qobuz", qobuzId, q) }
                    .getOrElse { Log.w(TAG, "gated qobuz $q: ${it.message}"); null } ?: continue
                // Qobuz was looked up by the ISRC itself and its answer checked against it.
                val result = hitAsSuccess(hit, "SpotiFLAC qobuz", info, true)
                if (result is Success) return result
            }
        }
        return null
    }

    /** Turns a gated hit into a Success, rejecting what is not full-length audio. */
    private suspend fun hitAsSuccess(
        hit: SpotiflacGated.GatedHit,
        provider: String,
        info: TrackInfo,
        isrcMatched: Boolean,
    ): Result? {
        if (hit.dashBase64.isNotBlank()) return Success(LosslessFile("", hit.dashBase64, provider, 24), isrcMatched)
        if (hit.url.isBlank()) return null
        if (!isFullTrack(hit.url, info.durationSec)) return null
        val quality = if (hit.quality > 0) hit.quality else 16
        return Success(LosslessFile(hit.url, "", provider, quality), isrcMatched)
    }

    /** The public Tidal community backends direct: search gave the id already. */
    private fun tidalDirect(tidal: TidalHit, info: TrackInfo, preferHiRes: Boolean): Result? {
        val qualities = if (preferHiRes) listOf("HI_RES", "LOSSLESS") else listOf("LOSSLESS")
        for (endpoint in LosslessRegistry.tidalEndpoints()) {
            if (endpoint.isBlank()) continue
            for (q in qualities) {
                val body = runCatching { get("$endpoint/track/?id=${tidal.id}&quality=$q", keyHeader()) }
                    .getOrElse { Log.w(TAG, "tidal $endpoint $q: ${it.message}"); continue }
                val fromResponse = runCatching { parseTidalTrack(body, endpoint) }.getOrElse {
                    Log.w(TAG, "tidal $endpoint $q parse: ${it.message}"); null
                }
                val file = fromResponse ?: continue
                if (file.url.isBlank()) {
                    // DASH manifest: storage-side remux makes it a real FLAC (24-bit).
                    return Success(file, isrcMatches(tidal.isrc, info.isrc))
                }
                if (!isFullTrack(file.url, info.durationSec)) continue
                return Success(file, isrcMatches(tidal.isrc, info.isrc))
            }
        }
        return null
    }

    /** Extracts the audio reference (url or base64 DASH manifest) from a /track/ response. */
    private fun parseTidalTrack(body: String, endpoint: String): LosslessFile? {
        val obj = JSONObject(body)
        val data = obj.optJSONObject("data") ?: return null
        val bits = data.optInt("bitDepth", 16).let { if (it in 8..32) it else 16 }
        val direct = data.optString("url").takeIf { it.startsWith("http") }
        if (direct != null) {
            if (!direct.contains(".flac", true)) return null
            val host = endpoint.removePrefix("https://").substringBefore('.')
            return LosslessFile(direct, "", "Tidal ($host)", bits)
        }
        val manifest = data.optString("manifest").takeIf { it.isNotBlank() } ?: return null
        val decoded = String(android.util.Base64.decode(manifest, android.util.Base64.DEFAULT), Charsets.UTF_8)
        if (!decoded.contains("<MPD")) {
            val m = JSONObject(decoded)
            val urls = m.optJSONArray("urls") ?: return null
            if (urls.length() != 1) return null
            val url = urls.optString(0).takeIf { it.contains(".flac", true) } ?: return null
            val host = endpoint.removePrefix("https://").substringBefore('.')
            return LosslessFile(url, "", "Tidal ($host)", bits)
        }
        return LosslessFile("", manifest, "Tidal hi-res", 24)
    }

    private fun amazon(spotifyId: String): Result? {
        for (endpoint in LosslessRegistry.amazonEndpoints()) {
            if (endpoint.isBlank()) continue
            val body = runCatching { get("$endpoint/api/resolve/spotify/$spotifyId", keyHeader()) }
                .getOrElse { Log.w(TAG, "amazon $endpoint: ${it.message}"); continue }
            val url = runCatching {
                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: json
                if (data.has("decryption_key") || json.has("decryption_key")) return@runCatching null
                listOf("url", "stream_url", "download_url", "streamUrl")
                    .firstNotNullOfOrNull { data.optString(it).takeIf(String::isNotBlank) }
            }.getOrElse { Log.w(TAG, "amazon $endpoint parse: ${it.message}"); null } ?: continue
            if (url.startsWith("http") && url.contains("flac", true)) {
                return Success(LosslessFile(url, "", "Amazon", 16), isrcMatched = false)
            }
        }
        return null
    }

    // ── Provider id discovery ─────────────────────────────────────────────────

    internal data class TidalHit(
        val id: String, val title: String, val artist: String, val durationSec: Int, val isrc: String,
    )

    /** Spotify id → Tidal track via the Tidal backend's own search, ISRC or title matched. */
    internal suspend fun tidalHit(info: TrackInfo): TidalHit? {
        val q = URLEncoder.encode("${info.title} ${info.artist}", "UTF-8")
        for (endpoint in LosslessRegistry.tidalEndpoints()) {
            if (endpoint.isBlank()) continue
            val hits = runCatching {
                val root = JSONObject(get("$endpoint/search/?s=$q", keyHeader()))
                val items: JSONArray = root.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
                (0 until items.length()).mapNotNull { i ->
                    val o = items.optJSONObject(i) ?: return@mapNotNull null
                    TidalHit(
                        id = o.optString("id"), title = o.optString("title"),
                        artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
                        durationSec = o.optInt("duration"), isrc = o.optString("isrc"),
                    )
                }
            }.getOrElse { Log.w(TAG, "tidal search $endpoint: ${it.message}"); emptyList() }
            pickHit(hits, info.isrc, info.title, info.artist, info.durationSec)?.let { return it }
        }
        return null
    }

    /**
     * The hit is the very recording Spotify plays: both sides have an ISRC and it is the
     * same. A title + artist match (no ISRC, or another one) is not an ISRC match.
     */
    internal fun isrcMatches(hitIsrc: String, spotifyIsrc: String): Boolean =
        spotifyIsrc.isNotBlank() && hitIsrc.equals(spotifyIsrc, ignoreCase = true)

    /**
     * The same recording: ISRC exact if Spotify has one; otherwise same title and
     * artist with a length within 3 s, so a live version or a cover never wins.
     */
    internal fun pickHit(
        hits: List<TidalHit>, isrc: String, title: String, artist: String, durationSec: Int,
    ): TidalHit? {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        hits.firstOrNull { isrc.isNotBlank() && it.isrc.equals(isrc, ignoreCase = true) && it.id.isNotBlank() }
            ?.let { return it }
        return hits.firstOrNull {
            it.id.isNotBlank() && key(it.title) == key(title) && key(it.artist) == key(artist) &&
                (durationSec <= 0 || kotlin.math.abs(it.durationSec - durationSec) <= 3)
        }
    }

    // Qobuz public search API (app_id/secret are the community's published constants).
    private const val QOBUZ_APP_ID = "712109809"
    private const val QOBUZ_APP_SECRET = "589be88e4538daea11f509d29e4a23b1"
    private const val QOBUZ_API_BASE = "https://www.qobuz.com/api.json/0.2"

    /** Qobuz track id by ISRC (signed search); null when Spotify has no ISRC or Qobuz no match. */
    internal fun qobuzIdForIsrc(isrc: String): String? {
        if (isrc.isBlank()) return null
        return runCatching {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val sigPayload = "tracksearch" + "limit" + "1" + "query" + isrc.trim() + ts + QOBUZ_APP_SECRET
            val sig = MessageDigest.getInstance("MD5").digest(sigPayload.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val resp = get(
                "$QOBUZ_API_BASE/track/search?query=${URLEncoder.encode(isrc.trim(), "UTF-8")}&limit=1" +
                    "&app_id=$QOBUZ_APP_ID&request_ts=$ts&request_sig=$sig",
                emptyMap(),
            )
            val items: JSONArray = JSONObject(resp).optJSONObject("tracks")?.optJSONArray("items") ?: JSONArray()
            // The search is full text: only a track carrying this very ISRC is the recording.
            (0 until items.length()).firstNotNullOfOrNull { i ->
                items.optJSONObject(i)?.takeIf { isrcMatches(it.optString("isrc"), isrc.trim()) }
                    ?.optString("id")?.takeIf(String::isNotBlank)
            }
        }.getOrElse { Log.w(TAG, "qobuz isrc search: ${it.message}"); null }
    }

    // ── Content checks ────────────────────────────────────────────────────────

    /**
     * The backends sometimes hand out a 30 s preview instead of the song. A FLAC
     * file states its length in STREAMINFO (first 42 bytes), so one tiny range
     * read tells.
     */
    internal fun isFullTrack(url: String, expectedSec: Int): Boolean {
        if (expectedSec <= 0 || url.isBlank()) return true
        val seconds = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Range", "bytes=0-41")
            }
            val head = try {
                if (conn.responseCode !in 200..299) ByteArray(0) else readHead(conn.inputStream, 42)
            } finally {
                conn.disconnect()
            }
            flacDurationSec(head)
        }.getOrNull() ?: return true // not a plain FLAC header: cannot tell here, keep it
        val full = seconds >= expectedSec - 5
        if (!full) Log.w(TAG, "rejected ${seconds}s preview (expected ${expectedSec}s)")
        return full
    }

    /** Reads at most [n] bytes (API-safe: no readNBytes), whatever the stream supplies. */
    private fun readHead(input: java.io.InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) b else b.copyOf(off)
    }

    /** Length from a FLAC STREAMINFO header, or null if [head] is not one. */
    internal fun flacDurationSec(head: ByteArray): Int? {
        if (head.size < 26 || String(head, 0, 4, Charsets.US_ASCII) != "fLaC") return null
        // Bytes 18..25: sample rate (20 bits), channels (3), bits/sample (5), total samples (36).
        var bits = 0L
        for (i in 18..25) bits = (bits shl 8) or (head[i].toLong() and 0xFF)
        val sampleRate = (bits ushr 44).toInt()
        val totalSamples = bits and 0xFFFFFFFFFL
        if (sampleRate <= 0 || totalSamples <= 0) return null
        return (totalSamples / sampleRate).toInt()
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun keyHeader() = mapOf("X-API-Key" to LosslessRegistry.API_KEY)

    internal fun get(urlString: String, headers: Map<String, String>): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${text.take(120)}")
            text
        } finally {
            conn.disconnect()
        }
    }
}
