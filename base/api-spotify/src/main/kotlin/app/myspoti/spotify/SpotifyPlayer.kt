package app.myspoti.spotify

import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.Player
import app.myspoti.api.PlayerState
import app.myspoti.api.Queue
import app.myspoti.api.Repeat
import app.myspoti.api.Uri
import app.myspoti.spotify.bridge.Bridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The Web Player's own player, driven through the bridge.
 *
 * Ads: hosts refuse the ad audio they know. Such an ad then stays current, buffering at
 * 0:00, and the page never moves on by itself: after [AD_STUCK_MS] the song after the last
 * one is played. An ad that really plays is left to its end. [adPlaying] says an ad is on;
 * ads are never exposed in [state] (the last song stays there, buffering).
 *
 * Stalls: on some phones the page stops between two songs (stuck past the end, paused at
 * the end with a next song, buffering for ever). After [STUCK_MS] the next song is played;
 * if that does not help either, [reloadHost] reloads the page and playback resumes there.
 */
class SpotifyPlayer(private val bridge: Bridge, private val scope: CoroutineScope) : Player {
    private val _state = MutableStateFlow<PlayerState?>(null)
    override val state: StateFlow<PlayerState?> = _state
    private val _queue = MutableStateFlow<Queue?>(null)
    override val queue: StateFlow<Queue?> = _queue

    private var lastTrackState: PlayerState? = null
    private val _adPlaying = MutableStateFlow(false)
    /** An ad is playing (the page plays it to its end). */
    val adPlaying: StateFlow<Boolean> = _adPlaying
    @Volatile private var adSince = 0L
    @Volatile private var adState: PlayerState? = null
    @Volatile private var pausedByUserAt = 0L
    /** The order given to the last [play] (context uri to songs), kept for a stall restart. */
    @Volatile private var lastOrder: Pair<Uri, List<Uri>>? = null

    /** Reloads the Web Player page (set by the host); used when a stalled player does not recover. */
    @Volatile var reloadHost: (suspend () -> Unit)? = null

    /** Streaming quality ("auto", "low", "normal", "high", "very_high"), kept for every page load. */
    @Volatile var audioQuality: String = "auto"
        private set

    init {
        scope.launch {
            bridge.events.collect { (type, payload) ->
                when (type) {
                    "state" -> onState(playerState(payload))
                    "queue" -> queue(payload)?.let { q -> _queue.value = q.copy(nextUp = q.nextUp.filter { it.kind != ItemKind.AD }) }
                }
            }
        }
        scope.launch { watchStalls() }
        // A reloaded page starts on "auto": the chosen quality is set again.
        scope.launch { bridge.ready.collect { if (it && audioQuality != "auto") applyAudioQuality() } }
    }

    private suspend fun watchStalls() {
        var key: String? = null
        var since = 0L
        var attempts = 0
        var adAttempts = 0
        while (true) {
            delay(WATCH_TICK_MS)
            val s = _state.value
            val now = System.currentTimeMillis()
            if (_adPlaying.value) {
                val ad = adState
                // A refused ad sits at 0:00, buffering; a real one plays to its end.
                val stuck = ad != null && (ad.isBuffering || ad.speed == 0.0) && now - adSince > AD_STUCK_MS
                if (stuck || now - adSince > AD_BREAK_MAX_MS) {
                    adAttempts++
                    val last = lastTrackState
                    // Playing on can land on the same refused ad again: after a few tries the
                    // page itself is reloaded, which drops the ad break.
                    if (adRecovery(adAttempts) == "next") {
                        bridge.logger?.invoke("ad stuck on ${ad?.item?.name}: playing the next song (attempt $adAttempts)")
                        last?.let { runCatching { playOn(it) } }
                    } else {
                        adAttempts = 0
                        bridge.logger?.invoke("ad stuck on ${ad?.item?.name}: reloading the player")
                        val reload = reloadHost
                        if (reload != null) runCatching {
                            reload()
                            withTimeoutOrNull(45_000) { delay(1_500); bridge.ready.first { it } } ?: return@runCatching
                            delay(1_500)
                            last?.let { playOn(it) }
                        } else last?.let { runCatching { playOn(it) } }
                    }
                    adSince = System.currentTimeMillis()
                }
                key = null
                continue
            }
            // A song really playing again: the ad break is over.
            if (s != null && !s.isPaused && !s.isBuffering) adAttempts = 0
            val reason = s?.let { stallOf(it, now, pausedByUserAt) }
            if (s == null || reason == null) { key = null; attempts = 0; continue }
            val k = "${s.item?.uri}|$reason"
            if (k != key) { key = k; since = now; attempts = 0; continue }
            if (now - since < (if (reason == "past the end") END_STUCK_MS else STUCK_MS)) continue
            since = now
            attempts++
            bridge.logger?.invoke("player stalled ($reason) on ${s.item?.name}, attempt $attempts")
            if (attempts == 1) runCatching { playOn(s) }
            else {
                attempts = 0
                val reload = reloadHost ?: continue
                runCatching {
                    reload()
                    withTimeoutOrNull(45_000) { delay(1_500); bridge.ready.first { it } } ?: return@runCatching
                    delay(1_500)
                    playOn(s)
                }
            }
        }
    }

    /** Plays the song after [s] (or [s] again when it is the last one) in its context. */
    private suspend fun playOn(s: PlayerState) {
        val ctx = s.contextUri
        val target = s.next?.takeIf { it.kind == ItemKind.TRACK }?.uri
            ?: _queue.value?.nextUp?.firstOrNull { it.kind == ItemKind.TRACK }?.uri
        when {
            ctx != null && target != null -> play(ctx, trackUri = target, order = lastOrder?.takeIf { it.first == ctx }?.second)
            target != null -> next()
            ctx != null -> play(ctx, trackUri = s.item?.uri)
        }
    }

    private fun onState(s: PlayerState?) {
        if (s?.item?.kind == ItemKind.AD) {
            // Keep showing the last real track, buffering, while the ad plays.
            if (!_adPlaying.value) adSince = System.currentTimeMillis()
            adState = s
            _adPlaying.value = true
            _state.value = lastTrackState?.copy(isBuffering = true) ?: s.copy(item = null)
            return
        }
        _adPlaying.value = false
        lastTrackState = s
        _state.value = s
    }


    override suspend fun play(contextUri: Uri, trackUri: Uri?, index: Int?, positionMs: Long?, order: List<Uri>?) {
        lastOrder = order?.let { contextUri to it }
        bridge.call("player.play", buildJsonObject {
            put("contextUri", contextUri)
            order?.let { uris -> putJsonArray("order") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
            trackUri?.let { put("trackUri", it) }
            index?.let { put("index", it) }
            positionMs?.let { put("positionMs", it) }
        })
    }

    override suspend fun pause() { pausedByUserAt = System.currentTimeMillis(); bridge.call("player.pause") }
    override suspend fun resume() { bridge.call("player.resume") }
    override suspend fun seek(positionMs: Long) { bridge.call("player.seek", buildJsonObject { put("positionMs", positionMs) }) }
    override suspend fun next() { bridge.call("player.next") }
    override suspend fun previous() { bridge.call("player.previous") }
    override suspend fun setShuffle(on: Boolean) { bridge.call("player.setShuffle", buildJsonObject { put("on", on) }) }

    override suspend fun setRepeat(mode: Repeat) {
        bridge.call("player.setRepeat", buildJsonObject { put("mode", mode.ordinal) })
    }

    override suspend fun addToQueue(uris: List<Uri>) {
        bridge.call("queue.add", buildJsonObject { putJsonArray("uris") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } })
    }

    override suspend fun removeFromQueue(items: List<NowPlaying>) {
        bridge.call("queue.remove", buildJsonObject {
            putJsonArray("items") {
                items.forEach { item -> addJsonObject { put("uri", item.uri); item.uid?.let { put("uid", it) } } }
            }
        })
    }

    override suspend fun clearQueue() { bridge.call("queue.clear") }

    private fun ref(item: NowPlaying) = buildJsonObject { put("uri", item.uri); item.uid?.let { put("uid", it) } }

    override suspend fun skipTo(item: NowPlaying) { bridge.call("queue.skipTo", ref(item)) }

    override suspend fun playNext(uris: List<Uri>) {
        val first = _queue.value?.queued?.firstOrNull()
        if (first == null) { addToQueue(uris); return }
        bridge.call("queue.insert", buildJsonObject {
            putJsonArray("uris") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("before", ref(first))
        })
    }

    override suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean) {
        bridge.call("queue.reorder", buildJsonObject {
            putJsonArray("items") { add(ref(item)) }
            put(if (after) "after" else "before", ref(target))
        })
    }

    override suspend fun setSpeed(speed: Double) {
        bridge.call("player.setSpeed", buildJsonObject { put("speed", speed.coerceIn(0.5, 3.5)) })
    }

    override suspend fun volume(): Double = bridge.call("volume.get").jsonPrimitive.double

    override suspend fun setVolume(level: Double) {
        bridge.call("volume.set", buildJsonObject { put("level", level.coerceIn(0.0, 1.0)) })
    }

    /** Sets the streaming quality now (when the page is up) and on every later page load. */
    suspend fun setAudioQuality(level: String) {
        audioQuality = level
        if (bridge.ready.value) applyAudioQuality()
    }

    private suspend fun applyAudioQuality() {
        runCatching { bridge.call("player.setAudioQuality", buildJsonObject { put("level", audioQuality) }) }
            .onFailure { bridge.logger?.invoke("audio quality $audioQuality not set: ${it.message}") }
    }

    /** Pulls the state once, e.g. right after the bridge is ready. */
    suspend fun refresh() {
        onState(playerState(bridge.call("player.state") as? JsonObject))
        _queue.value = queue(bridge.call("player.queue"))
    }

    companion object {
        /** A refused ad left current that long: the page will not move on by itself. */
        const val AD_STUCK_MS = 4_000L
        /** Longer than any ad break: the page is stuck in it. */
        const val AD_BREAK_MAX_MS = 4 * 60_000L
        const val STUCK_MS = 8_000L
        /** A song over with nothing after it: where an ad was left out. Next song quickly. */
        const val END_STUCK_MS = 1_000L
        private const val WATCH_TICK_MS = 2_000L
        /** Buffering longer than this on one song is a stall, not a slow network. */
        const val BUFFERING_STALL_MS = 25_000L
        /** Stuck ads skipped by playing on before the page is reloaded. */
        const val AD_SKIPS_BEFORE_RELOAD = 2

        /** What the [attempt]-th try on a stuck ad does: "next" (play on) or "reload". */
        fun adRecovery(attempt: Int): String = if (attempt <= AD_SKIPS_BEFORE_RELOAD) "next" else "reload"

        /**
         * Why [s] looks stalled at [now], or null. A song is stalled when its clock ran past
         * its end without a new song, when it paused itself at its end with a song after it,
         * or while it buffers too long.
         */
        fun stallOf(s: PlayerState, now: Long, pausedByUserAt: Long): String? {
            val item = s.item ?: return null
            if (item.kind != ItemKind.TRACK || item.durationMs <= 0) return null
            if (s.isPaused) {
                val atEnd = s.positionMs >= item.durationMs - 2_000
                val userPaused = now - pausedByUserAt < 20_000
                return if (atEnd && s.next != null && !userPaused) "paused at the end" else null
            }
            if (s.isBuffering) return if (now - s.timestampMs > BUFFERING_STALL_MS - STUCK_MS) "buffering" else null
            val clock = s.positionMs + ((now - s.timestampMs) * s.speed).toLong()
            return if (clock > item.durationMs + 1_500) "past the end" else null
        }
    }
}
