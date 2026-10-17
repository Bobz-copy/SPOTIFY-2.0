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
 * one is played. An ad that gets through (other markets serve ads from other places) is
 * muted at once and skipped the same way after [AD_PLAYING_SKIP_MS]; if the page still
 * holds on to it after one reload, it stays muted until it ends. [adPlaying] says an ad is
 * on; ads are never exposed in [state] (the last song stays there, buffering).
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
    /** The page's volume before an ad muted it, put back when the ad is over. */
    @Volatile private var volumeBeforeAd: Double? = null
    /** A pause asked through [pause]; it holds until playback is asked for again, never expires. */
    @Volatile private var pausedByUser = false
    /** The order given to the last [play] (context uri to songs), kept for a stall restart. */
    @Volatile private var lastOrder: Pair<Uri, List<Uri>>? = null

    /** The volume the user wants (0..1), put back when the page is left silent by mistake. */
    @Volatile private var wantedVolume: Double? = null
    /** The last [play] sent: (context, track, when), for a refused command's retry. */
    @Volatile private var lastPlay: Triple<Uri, Uri?, Long>? = null
    @Volatile private var refusedRetried = false
    private val _refused = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * Spotify refused a play even after the retry (free accounts in some countries can't
     * pick a song): the UI says so.
     */
    val refused: kotlinx.coroutines.flow.SharedFlow<Unit> = _refused

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
                    "refused" -> onRefused()
                }
            }
        }
        scope.launch { watchStalls() }
        // One collector, so a mute and the unmute after it never run out of order.
        scope.launch { _adPlaying.collect { if (it) muteForAd() else unmuteAfterAd() } }
        // A reloaded page starts on "auto": the chosen quality is set again.
        scope.launch {
            bridge.ready.collect {
                if (!it) return@collect
                if (audioQuality != "auto") applyAudioQuality()
                // The page keeps its volume: an app closed during a muted ad must not stay silent.
                runCatching { if (volume() == 0.0) setVolume(volumeBeforeAd ?: 1.0) }
            }
        }
    }

    /** Reloads the page, waits for it, then plays on from [s]. */
    private suspend fun reloadAndPlayOn(s: PlayerState?) {
        val reload = reloadHost ?: return
        runCatching {
            reload()
            withTimeoutOrNull(45_000) { delay(1_500); bridge.ready.first { it } } ?: return@runCatching
            delay(1_500)
            s?.let { playOn(it) }
        }
    }

    private suspend fun watchStalls() {
        var key: String? = null
        var since = 0L
        var attempts = 0
        var adAttempts = 0
        var adReloaded = false
        var frozenCheckAt = 0L
        var frozenReloadAt = 0L
        var tick = 0
        while (true) {
            delay(WATCH_TICK_MS)
            tick++
            val s = _state.value
            val now = System.currentTimeMillis()
            // A call timed out: is the page frozen (nothing answers, not even a sync call)?
            // Then reload at once instead of waiting for each 20 s timeout to pass.
            if (bridge.ready.value && now - bridge.lastTimeoutAt < FROZEN_WINDOW_MS && now - frozenCheckAt > FROZEN_WINDOW_MS) {
                frozenCheckAt = now
                if (!bridge.ping() && now - frozenReloadAt > FROZEN_RELOAD_GAP_MS) {
                    frozenReloadAt = now
                    bridge.logger?.invoke("page not answering: reloading the player")
                    reloadAndPlayOn(if (pausedByUser) null else lastTrackState)
                    key = null; attempts = 0
                    continue
                }
            }
            if (tick % VOLUME_CHECK_TICKS == 0) guardVolume(s)
            if (_adPlaying.value) {
                val ad = adState
                // A refused ad sits at 0:00, buffering; a real one plays to its end.
                val refused = ad != null && (ad.isBuffering || ad.speed == 0.0) && now - adSince > AD_STUCK_MS
                val playing = ad != null && !refused && now - adSince > AD_PLAYING_SKIP_MS && !adReloaded
                if (refused || playing || now - adSince > AD_BREAK_MAX_MS) {
                    adAttempts++
                    val last = lastTrackState
                    // Playing on can land on the same refused ad again: after a few tries the
                    // page itself is reloaded, which drops the ad break.
                    if (adRecovery(adAttempts) == "next") {
                        bridge.logger?.invoke("ad stuck on ${ad?.item?.name}: playing the next song (attempt $adAttempts)")
                        last?.let { runCatching { playOn(it) } }
                    } else {
                        adAttempts = 0
                        adReloaded = true
                        bridge.logger?.invoke("ad stuck on ${ad?.item?.name}: reloading the player")
                        if (reloadHost != null) reloadAndPlayOn(last) else last?.let { runCatching { playOn(it) } }
                    }
                    adSince = System.currentTimeMillis()
                }
                key = null
                continue
            }
            // A song really playing again: the ad break is over.
            if (s != null && !s.isPaused && !s.isBuffering) { adAttempts = 0; adReloaded = false }
            val reason = s?.let { stallOf(it, now, pausedByUser) }
            if (s == null || reason == null) { key = null; attempts = 0; continue }
            val k = "${s.item?.uri}|$reason"
            if (k != key) { key = k; since = now; attempts = 0; continue }
            if (now - since < (if (reason == "past the end") END_STUCK_MS else STUCK_MS)) continue
            since = now
            attempts++
            bridge.logger?.invoke("player stalled ($reason) on ${s.item?.name}, attempt $attempts")
            // A frozen page answers nothing: straight to the reload.
            if (attempts == 1 && !bridge.ping()) attempts = 2
            if (attempts == 1) runCatching { playOn(s) }
            else {
                attempts = 0
                reloadAndPlayOn(s)
            }
        }
    }

    /**
     * The page left silent while a song plays: a fade or an ad mute whose volume call was
     * lost (page busy, reloaded). The sound comes back to what the user wants.
     */
    private suspend fun guardVolume(s: PlayerState?) {
        if (s == null || s.isPaused || s.isBuffering || _adPlaying.value || fadeBackTo != null || fadeBack?.isActive == true) return
        val wanted = wantedVolume ?: 1.0
        if (wanted <= 0.0) return
        val v = runCatching { volume() }.getOrNull() ?: return
        if (v > 0.0) { if (volumeBeforeAd != null) volumeBeforeAd = null; return }
        bridge.logger?.invoke("page silent during a song: volume back to $wanted")
        volumeBeforeAd = null
        runCatching { setLevel(wanted) }
    }

    /** Spotify refused the last play: once, the list is played from its start instead. */
    private fun onRefused() {
        val last = lastPlay ?: return
        if (System.currentTimeMillis() - last.third > REFUSED_WINDOW_MS) return
        if (refusedRetried || last.second == null) { _refused.tryEmit(Unit); return }
        refusedRetried = true
        bridge.logger?.invoke("play refused by Spotify: playing the list without picking the song")
        scope.launch { runCatching { bridge.call("player.play", buildJsonObject { put("contextUri", last.first) }, timeoutMs = PLAY_TIMEOUT_MS) } }
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
            if (!_adPlaying.value) {
                adSince = System.currentTimeMillis()
            }
            adState = s
            _adPlaying.value = true
            _state.value = lastTrackState?.copy(isBuffering = true) ?: s.copy(item = null)
            return
        }
        _adPlaying.value = false
        lastTrackState = s
        _state.value = s
    }


    // ── Fades: a song cut off at full volume pops in the phone's speaker. Each skip or play
    // lowers the sound over ~0.1 s first and brings it back once the next song plays. ──

    /** The volume a fade comes back to, while one is under way. */
    @Volatile private var fadeBackTo: Double? = null
    private var fadeBack: kotlinx.coroutines.Job? = null

    private suspend fun setLevel(level: Double) {
        bridge.call("volume.set", buildJsonObject { put("level", level.coerceIn(0.0, 1.0)) })
    }

    private suspend fun withFade(block: suspend () -> Unit) {
        val s = _state.value
        // Nothing heard (paused, an ad muted): nothing to fade.
        if (s == null || s.isPaused || _adPlaying.value || volumeBeforeAd != null) { block(); return }
        val target = fadeBackTo ?: runCatching { volume() }.getOrNull()?.takeIf { it > 0.0 }?.also { wantedVolume = it } ?: run { block(); return }
        val from = s.item?.uri
        fadeBackTo = target
        fadeBack?.cancel()
        runCatching { for (f in FADE_STEPS) { setLevel(target * f); delay(FADE_STEP_MS) } }
        try {
            block()
        } finally {
            fadeBack = scope.launch {
                delay(FADE_STEP_MS * 3)
                // The next song is there (or give up waiting): the sound comes back. Buffering
                // is not waited for: nothing is heard meanwhile, and a page that keeps saying
                // "buffering" while it plays would leave the song's start muted.
                withTimeoutOrNull(FADE_BACK_MAX_MS) {
                    _state.first { it != null && !it.isPaused && (it.item?.uri != from || it.positionMs < 1_500) }
                }
                if (_adPlaying.value) { if (volumeBeforeAd == null) volumeBeforeAd = target; fadeBackTo = null; return@launch }
                val back = fadeBackTo ?: target
                runCatching { for (f in FADE_STEPS.reversed().drop(1)) { setLevel(back * f); delay(FADE_STEP_MS) }; setLevel(back) }
                    .onFailure { runCatching { setLevel(back) } }
                fadeBackTo = null
            }
        }
    }

    private suspend fun muteForAd() = runCatching {
        if (volumeBeforeAd == null) volumeBeforeAd = fadeBackTo ?: volume().takeIf { it > 0.0 } ?: wantedVolume?.takeIf { it > 0.0 } ?: 1.0
        bridge.call("volume.set", buildJsonObject { put("level", 0.0) })
    }

    private suspend fun unmuteAfterAd() = runCatching {
        val v = volumeBeforeAd ?: return@runCatching
        bridge.call("volume.set", buildJsonObject { put("level", v) })
        volumeBeforeAd = null
    }

    override suspend fun play(contextUri: Uri, trackUri: Uri?, index: Int?, positionMs: Long?, order: List<Uri>?) {
        lastOrder = order?.let { contextUri to it }
        lastPlay = Triple(contextUri, trackUri, System.currentTimeMillis())
        refusedRetried = false
        pausedByUser = false
        withFade {
            bridge.call("player.play", timeoutMs = PLAY_TIMEOUT_MS, args = buildJsonObject {
                put("contextUri", contextUri)
                order?.let { uris -> putJsonArray("order") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
                trackUri?.let { put("trackUri", it) }
                index?.let { put("index", it) }
                positionMs?.let { put("positionMs", it) }
            })
        }
    }

    override suspend fun pause() { pausedByUser = true; bridge.call("player.pause") }
    override suspend fun resume() { pausedByUser = false; bridge.call("player.resume") }
    override suspend fun seek(positionMs: Long) { bridge.call("player.seek", buildJsonObject { put("positionMs", positionMs) }) }
    override suspend fun next() { pausedByUser = false; withFade { bridge.call("player.next") } }
    override suspend fun previous() { pausedByUser = false; withFade { bridge.call("player.previous") } }
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

    override suspend fun skipTo(item: NowPlaying) { pausedByUser = false; withFade { bridge.call("queue.skipTo", ref(item)) } }

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
        wantedVolume = level.coerceIn(0.0, 1.0)
        if (fadeBackTo != null) fadeBackTo = level.coerceIn(0.0, 1.0)
        bridge.call("volume.set", buildJsonObject { put("level", level.coerceIn(0.0, 1.0)) })
    }

    /** Sets the streaming quality now (when the page is up) and on every later page load. */
    suspend fun setAudioQuality(level: String) {
        audioQuality = level
        if (bridge.ready.value) applyAudioQuality()
    }

    /** What the page plays now: bitrate (bit/s, 0 = unknown) and codec ("audio/mp4; codecs=…"). */
    suspend fun audioInfo(): Pair<Int, String> = runCatching {
        val o = bridge.call("player.audioInfo") as? JsonObject ?: return@runCatching 0 to ""
        (o["bitrate"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 0) to (o["mime"]?.jsonPrimitive?.content.orEmpty())
    }.getOrDefault(0 to "")

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
        /** Volume steps of a fade (down; reversed to come back). */
        val FADE_STEPS = listOf(0.55, 0.25, 0.08, 0.0)
        const val FADE_STEP_MS = 25L
        /** The sound always comes back after that long, whatever the page does. */
        const val FADE_BACK_MAX_MS = 2_500L
        /** Play commands: the page answers within a few seconds or not at all. */
        const val PLAY_TIMEOUT_MS = 10_000L
        /** A refusal that long after a play belongs to it. */
        const val REFUSED_WINDOW_MS = 5_000L
        /** After a timed-out call, the page is pinged at most once per window. */
        const val FROZEN_WINDOW_MS = 25_000L
        /** Never two "frozen page" reloads closer than that. */
        const val FROZEN_RELOAD_GAP_MS = 60_000L
        /** The volume is checked every that many ticks. */
        const val VOLUME_CHECK_TICKS = 3
        /** An ad that really plays (muted) is skipped after that long. */
        const val AD_PLAYING_SKIP_MS = 1_500L
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
        fun stallOf(s: PlayerState, now: Long, userPaused: Boolean): String? {
            val item = s.item ?: return null
            if (item.kind != ItemKind.TRACK || item.durationMs <= 0) return null
            if (s.isPaused) {
                val atEnd = s.positionMs >= item.durationMs - 2_000
                return if (atEnd && s.next != null && !userPaused) "paused at the end" else null
            }
            if (s.isBuffering) return if (now - s.timestampMs > BUFFERING_STALL_MS - STUCK_MS) "buffering" else null
            val clock = s.positionMs + ((now - s.timestampMs) * s.speed).toLong()
            return if (clock > item.durationMs + 1_500) "past the end" else null
        }
    }
}
