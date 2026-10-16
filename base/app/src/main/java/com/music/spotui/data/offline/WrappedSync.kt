package com.music.spotui.data.offline

import android.util.Log
import app.myspoti.host.android.RelayCredentials
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reports offline listens so they count where Spotify counts them (Wrapped, top lists).
 *
 * Only real listens are reported: a downloaded song that actually played on this
 * phone, for the time it actually played. The reporting replays what the Web
 * Player sends for a streamed listen (a captured Web Player session),
 * with the honest values: the played-time facts come from the
 * player, nothing is invented. Listens that could not be reported right away
 * (offline, data saver) are kept and re-reported once the network is back, so a
 * download-based history stays in sync.
 */
object WrappedSync {
    private const val TAG = "Wrapped"

    /** Spotify's own countable threshold: reaching it makes the listen real. */
    internal const val THRESHOLD_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The Spotify context of the list the downloads play from (a playlist/album uri); null = the track's album. */
    @Volatile var contextUri: String? = null

    /** One listening session of this app run (the melody session_id form). */
    private val sessionId = System.currentTimeMillis().toString()

    private var seq = 0
    private var current: Active? = null
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val queue = ArrayList<Listen>()
    private val persistLock = Any()
    @Volatile private var queueFile: File? = null

    /** A whole listen, kept until Spotify has it. The facts are frozen (real times). */
    data class Listen(
        val trackId: String,
        val albumId: String,
        val startedAt: Long,
        val playedMs: Long,
        val durationMs: Long,
        val threshold: Boolean,
        /** The Spotify context the play started from (playlist/album). */
        val context: String,
    )

    private class Active(val trackId: String, val albumId: String, val durationMs: Long, val context: String) {
        val playbackId = randomHex(16)
        val startedAt = System.currentTimeMillis()
        var playedMs = 0L
        var threshold = false
        var lastTick = System.currentTimeMillis()
        var playing = false
        var broken = false
        /** The last position the player showed, for the state PUTs. */
        var positionMs = 0L
    }

    /** Called with every state change of a local track (the LocalPlayer states). */
    fun onLocalState(t: LocalTrack, isPlaying: Boolean, pos: Long, dur: Long) {
        scope.launch { lock.withLock { step(t.id, isPlaying, pos, dur) } }
    }

    /** The local player stopped (a Spotify play took over): close the listen. */
    fun finish() = scope.launch { lock.withLock { finishLocked() } }

    /** Replays the kept listens: on every new play and after a failure that came back. */
    fun flush() = scope.launch { lock.withLock { flushLocked() } }

    /** Reads the queue back once at app start, then tries to sync it. */
    fun resumePending() {
        runCatching {
            val a = JSONArray(queueFile().readText())
            synchronized(persistLock) {
                queue.clear()
                (0 until a.length()).forEach { i ->
                    val o = a.getJSONObject(i)
                    queue.add(Listen(
                        o.getString("trackId"), o.optString("albumId"),
                        o.optLong("startedAt"), o.optLong("playedMs"),
                        o.optLong("durationMs"), o.optBoolean("threshold"),
                        o.optString("context", ""),
                    ))
                }
            }
            Log.i(TAG, "queued listens to sync: ${queue.size}")
        }
        if (queue.isNotEmpty()) flush()
    }

    private suspend fun step(localId: Long, isPlaying: Boolean, pos: Long, dur: Long) {
        // Runs under [lock] already (onLocalState): the Mutex is not reentrant.
        val offline = OfflineStore.byLocalId(localId)
        if (offline == null) {
            if (current != null) finishLocked() // a plain local file: nothing countable
            return
        }
        if (current?.trackId != offline.trackId) {
            finishLocked()
            val ctx = contextUri?.takeIf { it.isNotBlank() } ?: contextFallback(offline.albumId, offline.trackId)
            current = Active(offline.trackId, offline.albumId, dur, ctx).also { beginLocked(it, pos) }
        }
        val a = current ?: return
        val now = System.currentTimeMillis()
        a.positionMs = pos
        // Only the time since the last tick that was playing counts, never a pause.
        if (a.playing) a.playedMs += now - a.lastTick
        if (isPlaying) {
            if (!a.threshold && a.playedMs >= THRESHOLD_MS) {
                a.threshold = true
                if (!a.broken && !postState(a, "played_threshold_reached", pos, false)) a.broken = true
            }
        } else if (a.playing) { // paused: close this tick honestly
            if (!a.broken && !postState(a, "pause", pos, true)) a.broken = true
        }
        a.lastTick = now
        a.playing = isPlaying
    }

    private suspend fun beginLocked(a: Active, pos: Long) {
        a.lastTick = System.currentTimeMillis()
        Log.i(TAG, "${a.trackId}: listening, playback ${a.playbackId}")
        // The play command and the first states, as the player itself sends on a play.
        var broken = !postCommand(a)
        if (!broken && !postState(a, "before_track_load", 0, false)) broken = true
        if (!broken && !postState(a, "started_playing", pos, false)) broken = true
        if (!broken) postMelody(listOf(playbackStartMessage(a)))
        a.broken = broken
        flushLocked()
    }

    private suspend fun finishLocked() {
        val a = current ?: return
        current = null
        // The tail tick: what the last pause did not account for still counts.
        if (a.playing) a.playedMs += System.currentTimeMillis() - a.lastTick
        // The player only publishes on state changes: a song played straight through
        // crosses the threshold here, at its end.
        if (!a.threshold && a.playedMs >= THRESHOLD_MS) {
            a.threshold = true
            if (!a.broken && !postState(a, "played_threshold_reached", a.positionMs, false)) a.broken = true
        }
        val listen = listenOf(a)
        if (a.playedMs < 1_000) {
            Log.d(TAG, "${a.trackId}: dropped (${a.playedMs} ms)")
            return
        }
        var broken = a.broken
        if (!broken && !postState(a, "track_data_finalized", a.positionMs, false)) broken = true
        if (!broken && !postMelody(listOf(streamVerificationMessage(a, listen.playedMs)))) broken = true
        a.broken = broken
        if (listen.threshold && broken) {
            enqueue(listen)
            Log.i(TAG, "${a.trackId}: kept for later sync (${listen.playedMs} ms)")
        } else if (listen.threshold) {
            Log.i(TAG, "${a.trackId}: reported (${listen.playedMs} ms)")
        } else {
            Log.i(TAG, "${a.trackId}: under the threshold, not counted (${listen.playedMs} ms)")
        }
    }

    /** Replays the kept listens now that the network is back. Keeps what still fails. */
    private suspend fun flushLocked() {
        if (queue.isEmpty()) return
        if (OfflineStore.isOnline(Graph.app) && RelayCredentials.ready()) {
            val left = ArrayList<Listen>()
            for (listen in queue) {
                if (replay(listen)) Log.i(TAG, "synced: ${listen.trackId} (${listen.playedMs} ms)")
                else left.add(listen)
            }
            queue.clear(); queue.addAll(left)
            persistLocked()
        }
    }

    private fun enqueue(listen: Listen) = synchronized(persistLock) {
        queue.add(listen); persistLocked()
    }

    /** A kept listen replays on the same context it started from, its facts, its own fresh playback id. */
    private suspend fun replay(listen: Listen): Boolean {
        if (OfflineStore.isOnline(Graph.app) && !RelayCredentials.ready()) return false
        val a = Active(listen.trackId, listen.albumId, listen.durationMs, contextFor(listen)).apply {
            playedMs = listen.playedMs
            threshold = listen.threshold
            positionMs = listen.playedMs
        }
        return runCatching {
            val command = postCommand(a)
            val states = command &&
                postState(a, "before_track_load", 0, false) &&
                postState(a, "played_threshold_reached", listen.playedMs, false) &&
                postState(a, "track_data_finalized", listen.playedMs, false)
            val facts = states && postMelody(listOf(
                playbackStartMessage(a),
                streamVerificationMessage(a, listen.playedMs),
            ))
            command && states && facts
        }.getOrDefault(false)
    }

    // ── The three request shapes the player itself uses (capture-decoded) ────

    /** The play command that opens a listening session on Spotify's cluster. */
    private suspend fun postCommand(a: Active): Boolean {
        val ctx = a.context
        val body = JSONObject().put("command", JSONObject()
            .put("context", JSONObject().put("uri", ctx).put("url", "context://$ctx").put("metadata", JSONObject()))
            .put("play_origin", JSONObject()
                .put("feature_identifier", "playlist")
                .put("feature_version", FEATURE_VERSION)
                .put("referrer_identifier", "library"))
            .put("options", JSONObject()
                .put("license", "tft")
                .put("skip_to", JSONObject())
                .put("player_options_override", JSONObject()))
            .put("logging_params", JSONObject().put("page_instance_ids", JSONArray()).put("interaction_ids", JSONArray()))
            .put("endpoint", "play"))
        return send(
            "/connect-state/v1/player/command/from/${RelayCredentials.deviceId}/to/${RelayCredentials.deviceId}",
            body,
        )
    }

    /** The play state machine: milestones at the real position, never a padded one. */
    private suspend fun postState(a: Active, debugSource: String, position: Long, paused: Boolean): Boolean {
        val body = JSONObject()
            .put("seq_num", seq++)
            .put("state_ref", JSONObject()
                .put("state_machine_id", randomB64(21))
                .put("state_id", a.playbackId)
                .put("paused", paused))
            .put("sub_state", JSONObject()
                .put("playback_speed", if (paused) 0 else 1)
                .put("position", position)
                .put("duration", a.durationMs)
                .put("media_type", "AUDIO")
                .put("bitrate", 128000) // the player's own report shape for a streamed track
                .put("audio_quality", "HIGH")
                .put("format", 10)
                .put("is_video_on", false))
            .put("debug_source", debugSource)
        return send("/track-playback/v1/devices/${RelayCredentials.deviceId}/state", body)
    }

    /** The SDK telemetry batch: when a play started, and how long it really played. */
    private suspend fun postMelody(messages: List<JSONObject>): Boolean {
        val body = JSONObject()
            .put("messages", JSONArray().apply { messages.forEach { put(it) } })
            .put("sdk_id", "harmony:4.81.1")
            .put("platform", PLAY_PLATFORM)
            .put("client_version", "0.0.0")
        return send("/melody/v1/msg/batch", body)
    }

    private fun playbackStartMessage(a: Active) = JSONObject()
        .put("type", "jssdk_playback_start")
        .put("message", JSONObject()
            .put("play_track", a.trackUri())
            .put("playback_id", a.playbackId)
            .put("session_id", sessionId)
            .put("ms_start_position", 0)
            .put("initially_paused", false)
            .put("feature_identifier", ""))

    private fun streamVerificationMessage(a: Active, playedMs: Long) = JSONObject()
        .put("type", "track_stream_verification")
        .put("message", JSONObject()
            .put("play_track", a.trackUri())
            .put("playback_id", a.playbackId)
            .put("ms_played", playedMs)
            .put("ms_nominal_played", playedMs)
            .put("session_id", sessionId)
            .put("sequence_id", 0)
            .put("next_playback_id", "")
            .put("playback_service", "track-playback"))

    private suspend fun send(path: String, body: JSONObject): Boolean {
        val c = RelayCredentials
        val host = c.clusterHost.ifBlank { "spclient.wg.spotify.com" }
        val url = "https://$host$path"
        try {
            val req = Request.Builder().url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", c.authorization)
                .header("Client-Token", c.clientToken)
                .header("User-Agent", PLAY_UA)
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.d(TAG, "${path.substringBefore('?')} -> ${resp.code}")
                    return true
                }
                Log.w(TAG, "${path.substringBefore('?')} -> ${resp.code}")
                if (resp.code == 401) c.authorization = "" // the page refreshes it on its next call
                return false
            }
        } catch (e: Exception) {
            Log.d(TAG, "${path.substringBefore('?')} failed: ${e.message}")
            return false
        }
    }

    // ── Queue persistence (the offline part) ─────────────────────────────────

    private fun listenOf(a: Active) = Listen(
        trackId = a.trackId, albumId = a.albumId, startedAt = a.startedAt,
        playedMs = a.playedMs, durationMs = a.durationMs, threshold = a.threshold,
        context = a.context,
    )

    private fun persistLocked() = synchronized(persistLock) {
        runCatching {
            val a = JSONArray()
            queue.forEach { l ->
                a.put(JSONObject()
                    .put("trackId", l.trackId).put("albumId", l.albumId)
                    .put("startedAt", l.startedAt).put("playedMs", l.playedMs)
                    .put("durationMs", l.durationMs).put("threshold", l.threshold)
                    .put("context", l.context))
            }
            queueFile().writeText(a.toString())
        }
        Unit
    }

    private fun queueFile() = queueFile ?: File(Graph.app.filesDir, "wrapped_queue.json").also { queueFile = it }

    // ── Pure helpers (unit-tested) ────────────────────────────────────────────

    /** A deferred listen replays on the context it started from. */
    internal fun contextFor(l: Listen): String =
        l.context.ifBlank { contextFallback(l.albumId, l.trackId) }

    private fun contextFallback(albumId: String, trackId: String): String = when {
        albumId.isNotBlank() -> "spotify:album:$albumId"
        else -> "spotify:track:$trackId"
    }

    /** The UA the Web Player's own requests use (one device, one voice). */
    internal const val PLAY_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"

    /** The melody platform string the player sends. */
    internal const val PLAY_PLATFORM = "web_player linux undefined;chrome 155.0.0.0;desktop"

    /** The player version string, as the capture kept it. */
    internal const val FEATURE_VERSION = "web-player_2026-10-15_1792092088506_c66299947cb4"

    internal fun randomHex(bytes: Int): String =
        (0 until bytes).joinToString("") { "%02x".format(java.security.SecureRandom().nextInt(256)) }

    internal fun randomB64(bytes: Int): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) },
    )

    private fun Active.trackUri() = "spotify:track:$trackId"
}
