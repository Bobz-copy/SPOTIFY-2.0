package app.myspoti.spotify.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class BridgeException(message: String) : Exception(message)

/** Request/response over a [BridgeHost], plus the page's pushed events. */
class Bridge(private val host: BridgeHost, scope: CoroutineScope) {
    private val ids = AtomicInteger(0)

    /** Diagnostics: receives one line per call, result and page message. */
    @Volatile var logger: ((String) -> Unit)? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /** `state` and `queue` messages, raw payload of each. */
    private val _events = MutableSharedFlow<Pair<String, JsonElement>>(extraBufferCapacity = 64)
    val events: SharedFlow<Pair<String, JsonElement>> = _events

    init {
        scope.launch {
            host.messages.collect { raw -> handle(raw) }
        }
    }

    private fun handle(raw: String) {
        val msg = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        logger?.let { log ->
            val type = msg["type"]?.jsonPrimitive?.content
            if (type == "state") {
                val s = msg["state"] as? JsonObject
                log("page state: " + (s?.get("track") as? JsonObject)?.get("name") + " paused=" + s?.get("isPaused") + " buffering=" + s?.get("isBuffering"))
            } else if (type == "result") {
                // Results are logged by call(); never write their values (tokens) here.
            } else if (type != "queue") log("page $type: " + raw.take(300))
        }
        when (msg["type"]?.jsonPrimitive?.content) {
            "ready" -> _ready.value = true
            "result" -> {
                val id = msg["id"]?.jsonPrimitive?.int ?: return
                val waiter = pending.remove(id) ?: return
                if (msg["ok"]?.jsonPrimitive?.boolean == true) waiter.complete(msg["value"] ?: JsonNull)
                else waiter.completeExceptionally(BridgeException(msg["error"]?.jsonPrimitive?.content ?: "bridge error"))
            }
            "state" -> _events.tryEmit("state" to (msg["state"] ?: JsonNull))
            "queue" -> _events.tryEmit("queue" to (msg["queue"] ?: JsonNull))
            "error" -> _ready.value = false
        }
    }

    /** Injects the bridge into the current page. Hosts call this after each page load. */
    suspend fun inject() {
        _ready.value = false
        host.evaluate(BridgeScript.source)
    }

    suspend fun call(method: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 20_000): JsonElement {
        val id = ids.incrementAndGet()
        val waiter = CompletableDeferred<JsonElement>()
        pending[id] = waiter
        try {
            val started = System.currentTimeMillis()
            logger?.invoke("call #$id $method ${args.toString().take(200)}")
            host.evaluate("window.Myspoti && window.Myspoti.call($id, ${json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(method))}, $args).catch(() => {})")
            return try {
                withTimeout(timeoutMs) { waiter.await() }.also {
                    val shown = if (method == "token.get") "(token hidden)" else it.toString().take(200)
                    logger?.invoke("call #$id $method ok in ${System.currentTimeMillis() - started} ms: $shown")
                }
            } catch (e: Exception) {
                logger?.invoke("call #$id $method FAILED after ${System.currentTimeMillis() - started} ms: ${e.message}")
                throw e
            }
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Calls `service.method(...args)` on one of the page's services and returns its JSON copy. */
suspend fun Bridge.service(service: String, method: String, vararg args: JsonElement): JsonElement =
    call("service.call", kotlinx.serialization.json.buildJsonObject {
        put("service", kotlinx.serialization.json.JsonPrimitive(service))
        put("method", kotlinx.serialization.json.JsonPrimitive(method))
        put("args", kotlinx.serialization.json.JsonArray(args.toList()))
    })
