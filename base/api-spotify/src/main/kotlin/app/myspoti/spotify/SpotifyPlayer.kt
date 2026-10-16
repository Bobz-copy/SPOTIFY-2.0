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
 * Ads: hosts refuse the ad audio, so the page skips an ad within a second. If an ad item
 * still stays current longer than [AD_GRACE_MS] (new ad host, video ad), the next real
 * track is played. Ads are never exposed in [state].
 */
class SpotifyPlayer(private val bridge: Bridge, private val scope: CoroutineScope) : Player {
    private val _state = MutableStateFlow<PlayerState?>(null)
    override val state: StateFlow<PlayerState?> = _state
    private val _queue = MutableStateFlow<Queue?>(null)
    override val queue: StateFlow<Queue?> = _queue

    private var lastTrackState: PlayerState? = null
    private var adWatch: Job? = null

    init {
        scope.launch {
            bridge.events.collect { (type, payload) ->
                when (type) {
                    "state" -> onState(playerState(payload))
                    "queue" -> queue(payload)?.let { q -> _queue.value = q.copy(nextUp = q.nextUp.filter { it.kind != ItemKind.AD }) }
                }
            }
        }
    }

    private fun onState(s: PlayerState?) {
        if (s?.item?.kind == ItemKind.AD) {
            // Keep showing the last real track, buffering, while the ad is being dropped.
            _state.value = lastTrackState?.copy(isBuffering = true) ?: s.copy(item = null)
            if (adWatch?.isActive != true) adWatch = scope.launch { skipAdIfStuck(s.contextUri) }
            return
        }
        adWatch?.cancel()
        lastTrackState = s
        _state.value = s
    }

    private suspend fun skipAdIfStuck(contextUri: Uri?) {
        delay(AD_GRACE_MS)
        val next = _queue.value?.nextUp?.firstOrNull { it.kind == ItemKind.TRACK } ?: return
        if (contextUri != null) runCatching { play(contextUri, trackUri = next.uri) }
    }

    override suspend fun play(contextUri: Uri, trackUri: Uri?, index: Int?, positionMs: Long?) {
        bridge.call("player.play", buildJsonObject {
            put("contextUri", contextUri)
            trackUri?.let { put("trackUri", it) }
            index?.let { put("index", it) }
            positionMs?.let { put("positionMs", it) }
        })
    }

    override suspend fun pause() { bridge.call("player.pause") }
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

    /** Pulls the state once, e.g. right after the bridge is ready. */
    suspend fun refresh() {
        onState(playerState(bridge.call("player.state") as? JsonObject))
        _queue.value = queue(bridge.call("player.queue"))
    }

    companion object {
        const val AD_GRACE_MS = 1_000L
    }
}
