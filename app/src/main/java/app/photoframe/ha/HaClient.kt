package app.photoframe.ha

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class HaEntity(val entityId: String, val state: String, val attributes: JsonObject) {
    val domain: String get() = entityId.substringBefore('.')
    val name: String get() = attributes.s("friendly_name") ?: entityId.substringAfter('.').replace('_', ' ')
    fun attr(key: String): String? = attributes.s(key)
}

class HaException(msg: String) : Exception(msg)

/**
 * Minimal Home Assistant websocket API client: auth, commands, subscriptions,
 * binary frames (for Assist audio), compressed entity subscriptions.
 */
class HaClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val urlsProvider: () -> List<String>,
    private val tokenProvider: () -> String,
    private val onWorkingUrl: (String) -> Unit = {},
) {
    enum class Status { DISABLED, CONNECTING, CONNECTED, AUTH_FAILED, DISCONNECTED }

    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow(Status.DISABLED)
    val status: StateFlow<Status> = _status

    private val _lastError = MutableStateFlow<String?>(null)
    /** Human-readable reason for the last failed connection attempt. */
    val lastError: StateFlow<String?> = _lastError
    @Volatile private var activeUrl: String? = null
    private var candidateIndex = 0

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    val entities: StateFlow<Map<String, HaEntity>> = _entities

    private var ws: WebSocket? = null
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val subscriptions = ConcurrentHashMap<Int, (JsonObject) -> Unit>()
    private var watched: List<String> = emptyList()
    private var entitySubId: Int? = null
    private var loopJob: Job? = null
    private var connectedSignal = CompletableDeferred<Unit>()

    private fun candidates(): List<String> =
        urlsProvider().map { it.trim().trimEnd('/') }.filter { it.startsWith("http") }.distinct()

    /** URL currently in use (or the first candidate before we've connected). */
    val baseUrl: String get() = activeUrl ?: candidates().firstOrNull().orEmpty()
    val configured: Boolean get() = candidates().isNotEmpty() && tokenProvider().isNotBlank()

    /** (Re)start the connection loop, e.g. after config changes. */
    fun restart() {
        loopJob?.cancel()
        ws?.close(1000, "restart"); ws = null
        failAll("restarting")
        activeUrl = null
        candidateIndex = 0
        if (!configured) { _status.value = Status.DISABLED; return }
        loopJob = scope.launch {
            var backoff = 1000L
            var failedInRound = 0
            while (true) {
                val urls = candidates()
                if (urls.isEmpty()) { _status.value = Status.DISABLED; return@launch }
                val url = urls[candidateIndex % urls.size]
                _status.value = Status.CONNECTING
                val closed = CompletableDeferred<Unit>()
                connectedSignal = CompletableDeferred()
                open(url, closed)
                closed.await()
                if (_status.value == Status.AUTH_FAILED) { delay(60_000); continue }
                val wasConnected = _status.value == Status.CONNECTED
                _status.value = Status.DISCONNECTED
                if (wasConnected) {
                    // Same URL worked before; just retry it.
                    backoff = 1000L; failedInRound = 0
                } else {
                    // Couldn't reach this URL: try the next candidate right away,
                    // and only back off once every candidate has failed.
                    candidateIndex++
                    if (++failedInRound < urls.size) continue
                    failedInRound = 0
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    private fun open(url: String, closed: CompletableDeferred<Unit>) {
        val wsUrl = url.replaceFirst("http", "ws") + "/api/websocket"
        val req = Request.Builder().url(wsUrl).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val el = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return
                // HA may batch messages into arrays
                val msgs = if (el is JsonArray) el.map { it.jsonObject } else listOf(el.jsonObject)
                msgs.forEach { handle(webSocket, url, it) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure ($url): ${t.message}")
                _lastError.value = "$url: " + (response?.let { "HTTP ${it.code}" } ?: t.message ?: t.javaClass.simpleName)
                finish(webSocket)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(webSocket)
            private fun finish(socket: WebSocket) {
                if (ws === socket) { ws = null; failAll("disconnected") }
                closed.complete(Unit)
            }
        })
    }

    private fun handle(socket: WebSocket, url: String, msg: JsonObject) {
        when (msg.s("type")) {
            "auth_required" -> socket.send(buildJsonObject {
                put("type", "auth"); put("access_token", tokenProvider())
            }.toString())
            "auth_ok" -> {
                activeUrl = url
                _lastError.value = null
                onWorkingUrl(url)
                _status.value = Status.CONNECTED
                connectedSignal.complete(Unit)
                // enable message coalescing
                scope.launch {
                    runCatching { command(buildJsonObject { put("type", "supported_features"); putJsonObject("features") { put("coalesce_messages", 1) } }) }
                    resubscribeEntities()
                }
            }
            "auth_invalid" -> {
                _lastError.value = "$url: access token rejected"
                _status.value = Status.AUTH_FAILED; socket.close(1000, "auth invalid")
            }
            "result" -> {
                val id = (msg["id"] as? JsonPrimitive)?.intOrNull ?: return
                pending.remove(id)?.complete(msg)
            }
            "event" -> {
                val id = (msg["id"] as? JsonPrimitive)?.intOrNull ?: return
                val ev = msg["event"] as? JsonObject ?: return
                subscriptions[id]?.invoke(ev)
            }
            "pong" -> {
                val id = (msg["id"] as? JsonPrimitive)?.intOrNull ?: return
                pending.remove(id)?.complete(msg)
            }
        }
    }

    private fun failAll(reason: String) {
        pending.values.forEach { it.completeExceptionally(HaException(reason)) }
        pending.clear()
        subscriptions.clear()
        entitySubId = null
    }

    suspend fun awaitConnected(timeoutMs: Long = 10_000) {
        if (_status.value == Status.CONNECTED) return
        if (!configured) throw HaException("Home Assistant not configured")
        withTimeout(timeoutMs) { connectedSignal.await() }
    }

    /** Send a command and wait for its result. Returns the `result` field. */
    suspend fun command(payload: JsonObject, timeoutMs: Long = 15_000): JsonElement? {
        val socket = ws ?: throw HaException("not connected")
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        socket.send(JsonObject(payload + ("id" to JsonPrimitive(id))).toString())
        val res = try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(id) }
        if (res["success"] == JsonPrimitive(false)) {
            val err = res["error"] as? JsonObject
            throw HaException(err?.s("message") ?: "command failed")
        }
        return res["result"]
    }

    /**
     * Send a subscription-style command (subscribe_*, assist_pipeline/run).
     * [onEvent] receives each `event` payload. Returns the message id.
     */
    suspend fun subscribe(payload: JsonObject, onEvent: (JsonObject) -> Unit): Int {
        val socket = ws ?: throw HaException("not connected")
        val id = ids.getAndIncrement()
        subscriptions[id] = onEvent
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        socket.send(JsonObject(payload + ("id" to JsonPrimitive(id))).toString())
        val res = try { withTimeout(15_000) { d.await() } } finally { pending.remove(id) }
        if (res["success"] == JsonPrimitive(false)) {
            subscriptions.remove(id)
            throw HaException((res["error"] as? JsonObject)?.s("message") ?: "subscribe failed")
        }
        return id
    }

    fun unsubscribeLocal(id: Int) { subscriptions.remove(id) }

    suspend fun unsubscribe(id: Int) {
        subscriptions.remove(id)
        runCatching { command(buildJsonObject { put("type", "unsubscribe_events"); put("subscription", id) }) }
    }

    fun sendBinary(bytes: ByteArray): Boolean = ws?.send(bytes.toByteString()) ?: false
    fun sendBinary(bytes: ByteString): Boolean = ws?.send(bytes) ?: false

    // ---------------------------------------------------------------- entities

    fun watchEntities(ids: List<String>) {
        val sorted = ids.distinct().sorted()
        if (sorted == watched) return
        watched = sorted
        scope.launch { resubscribeEntities() }
    }

    private suspend fun resubscribeEntities() {
        if (_status.value != Status.CONNECTED) return
        entitySubId?.let { unsubscribe(it) }
        entitySubId = null
        _entities.update { m -> m.filterKeys { it in watched } }
        if (watched.isEmpty()) return
        runCatching {
            entitySubId = subscribe(buildJsonObject {
                put("type", "subscribe_entities")
                putJsonArray("entity_ids") { watched.forEach { add(JsonPrimitive(it)) } }
            }) { ev -> applyCompressed(ev) }
        }.onFailure { Log.w(TAG, "subscribe_entities failed", it) }
    }

    /** Apply the compressed state format used by subscribe_entities. */
    private fun applyCompressed(ev: JsonObject) {
        _entities.update { current ->
            val m = current.toMutableMap()
            (ev["a"] as? JsonObject)?.forEach { (id, v) ->
                val o = v.jsonObject
                m[id] = HaEntity(id, o.s("s") ?: "unknown", (o["a"] as? JsonObject) ?: JsonObject(emptyMap()))
            }
            (ev["c"] as? JsonObject)?.forEach { (id, v) ->
                val old = m[id] ?: return@forEach
                val o = v.jsonObject
                var state = old.state
                val attrs = old.attributes.toMutableMap()
                (o["+"] as? JsonObject)?.let { plus ->
                    plus.s("s")?.let { state = it }
                    (plus["a"] as? JsonObject)?.let { attrs.putAll(it) }
                }
                (o["-"] as? JsonObject)?.let { minus ->
                    (minus["a"] as? JsonArray)?.forEach { k -> (k as? JsonPrimitive)?.contentOrNull?.let(attrs::remove) }
                }
                m[id] = HaEntity(id, state, JsonObject(attrs))
            }
            (ev["r"] as? JsonArray)?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(m::remove) }
            m
        }
    }

    // ---------------------------------------------------------------- helpers

    suspend fun callService(domain: String, service: String, entityId: String? = null, data: JsonObject? = null, returnResponse: Boolean = false): JsonElement? =
        command(buildJsonObject {
            put("type", "call_service"); put("domain", domain); put("service", service)
            if (data != null) put("service_data", data)
            if (entityId != null) putJsonObject("target") { put("entity_id", entityId) }
            if (returnResponse) put("return_response", true)
        })

    suspend fun toggle(entity: HaEntity) {
        when (entity.domain) {
            "scene", "script" -> callService(entity.domain, "turn_on", entity.entityId)
            "button", "input_button" -> callService(entity.domain, "press", entity.entityId)
            "lock" -> callService("lock", if (entity.state == "locked") "unlock" else "lock", entity.entityId)
            "cover" -> callService("cover", "toggle", entity.entityId)
            "media_player" -> callService("media_player", "media_play_pause", entity.entityId)
            else -> callService("homeassistant", "toggle", entity.entityId)
        }
    }

    suspend fun dailyForecast(entityId: String): List<JsonObject> {
        val res = callService("weather", "get_forecasts", entityId, buildJsonObject { put("type", "daily") }, returnResponse = true)
        val resp = (res as? JsonObject)?.get("response") as? JsonObject ?: return emptyList()
        val f = (resp[entityId] as? JsonObject)?.get("forecast") as? JsonArray ?: return emptyList()
        return f.mapNotNull { it as? JsonObject }
    }

    data class Pipeline(val id: String, val name: String)

    suspend fun pipelines(): List<Pipeline> {
        val res = command(buildJsonObject { put("type", "assist_pipeline/pipeline/list") }) as? JsonObject ?: return emptyList()
        return (res["pipelines"] as? JsonArray).orEmpty().mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            Pipeline(o.s("id") ?: return@mapNotNull null, o.s("name") ?: "Pipeline")
        }
    }

    /** Absolute URL for an HA-relative path like /api/tts_proxy/... */
    fun absolute(pathOrUrl: String): String =
        if (pathOrUrl.startsWith("http")) pathOrUrl else baseUrl + pathOrUrl

    companion object { private const val TAG = "HaClient" }
}

internal fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
