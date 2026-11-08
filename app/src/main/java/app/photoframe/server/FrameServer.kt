package app.photoframe.server

import android.graphics.Bitmap
import android.util.Log
import app.photoframe.AppGraph
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.path
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class FrameServer(private val g: AppGraph) {
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }
    private var engine: ApplicationEngine? = null
    private val random = SecureRandom()

    @Volatile private var pairCode: String? = null
    @Volatile private var pairExpires = 0L
    @Volatile private var pairFailures = 0
    private var photoCache: Pair<Long, ByteArray>? = null

    fun restart(port: Int) {
        runCatching { engine?.stop(200, 1000) }
        engine = runCatching {
            embeddedServer(CIO, port = port, host = "0.0.0.0") {
                install(WebSockets) { pingPeriodMillis = 30_000; timeoutMillis = 60_000 }
                // Reject unauthorized websocket upgrades with a plain 401 (HA uses this to start re-pairing).
                intercept(ApplicationCallPipeline.Plugins) {
                    if (call.request.path() == "/api/v1/ws" && !call.authorized()) {
                        call.unauthorized(); finish()
                    }
                }
                routing {
                    get("/api/v1/info") { call.json(json.encodeToString(WireInfo.serializer(), g.info())) }

                    post("/api/v1/pair/start") {
                        val code = (0 until 6).map { random.nextInt(10) }.joinToString("")
                        pairCode = code; pairFailures = 0
                        pairExpires = System.currentTimeMillis() + 120_000
                        g.ui.pairingCode.value = code
                        if (app.photoframe.BuildConfig.DEBUG) Log.d(TAG, "pairing code: $code")
                        g.scope.launch { delay(120_000); if (pairCode == code) { pairCode = null; g.ui.pairingCode.value = null } }
                        call.json("""{"ok":true}""")
                    }

                    post("/api/v1/pair/finish") {
                        val body = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                        val code = body?.s("code")?.trim()
                        val expected = pairCode
                        if (expected == null || System.currentTimeMillis() > pairExpires || code != expected) {
                            if (expected != null && ++pairFailures >= 5) { pairCode = null; g.ui.pairingCode.value = null }
                            call.json("""{"error":"invalid_code"}""", HttpStatusCode.Unauthorized); return@post
                        }
                        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                        val client = body.s("client_name") ?: "Home Assistant"
                        g.config.update { it.copy(serverTokens = it.serverTokens + (token to client)) }
                        pairCode = null; g.ui.pairingCode.value = null
                        call.json(buildJsonObject { put("token", token); put("id", g.config.value.frameId) }.toString())
                    }

                    get("/api/v1/state") {
                        if (!call.authorized()) return@get call.unauthorized()
                        call.json(json.encodeToString(WireState.serializer(), g.snapshot()))
                    }

                    post("/api/v1/command") {
                        if (!call.authorized()) return@post call.unauthorized()
                        val body = runCatching { json.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
                        val res = runCommand(body?.s("command"), body?.get("params") as? JsonObject)
                        call.json(buildJsonObject {
                            put("success", res.first); put("error", res.second?.let(::JsonPrimitive) ?: JsonNull); put("result", res.third)
                        }.toString(), if (res.first) HttpStatusCode.OK else HttpStatusCode.BadRequest)
                    }

                    get("/api/v1/current_photo.jpg") {
                        if (!call.authorized()) return@get call.unauthorized()
                        val bytes = currentPhotoJpeg() ?: return@get call.respondText("no photo", status = HttpStatusCode.NotFound)
                        call.respondBytes(bytes, ContentType.Image.JPEG)
                    }

                    webSocket("/api/v1/ws") {
                        if (!call.authorized()) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized")); return@webSocket }
                        val out = Channel<String>(Channel.BUFFERED)
                        val sender = launch { for (m in out) send(Frame.Text(m)) }
                        val stateJob = launch {
                            g.wireState.collect { st ->
                                out.send("""{"type":"state","data":${json.encodeToString(WireState.serializer(), st)}}""")
                            }
                        }
                        val eventJob = launch {
                            var lastTouch = 0L
                            merge(
                                g.assist.events.map { e -> e.name to JsonObject(e.data.mapValues { JsonPrimitive(it.value) }) },
                                g.slideshow.photoChanged.drop(0).map { "photo_changed" to buildJsonObject { put("asset_id", it) } },
                                g.media.ended.map { "media_ended" to JsonObject(emptyMap()) },
                                g.ui.touch.map { "touch" to JsonObject(emptyMap()) },
                            ).collect { (name, data) ->
                                if (name == "touch") {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTouch < 10_000) return@collect
                                    lastTouch = now
                                }
                                out.send(buildJsonObject { put("type", "event"); put("event", name); put("data", data) }.toString())
                            }
                        }
                        try {
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                val msg = runCatching { json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                                when (msg.s("type")) {
                                    "ping" -> out.send("""{"type":"pong"}""")
                                    "command" -> {
                                        val id = msg["id"]
                                        launch {
                                            val (ok, err, result) = runCommand(msg.s("command"), msg["params"] as? JsonObject)
                                            out.send(buildJsonObject {
                                                put("type", "result"); put("id", id ?: JsonNull); put("success", ok)
                                                put("error", err?.let(::JsonPrimitive) ?: JsonNull); put("result", result)
                                            }.toString())
                                        }
                                    }
                                }
                            }
                        } finally {
                            stateJob.cancel(); eventJob.cancel(); out.close(); sender.cancel()
                        }
                    }
                }
            }.start(wait = false)
        }.onFailure { Log.e(TAG, "server failed to start on $port", it) }.getOrNull()
        Log.i(TAG, "server listening on $port")
    }

    private suspend fun runCommand(command: String?, params: JsonObject?): Triple<Boolean, String?, JsonElement> {
        if (command == null) return Triple(false, "missing command", JsonNull)
        return try {
            Triple(true, null, g.commands.execute(command, params ?: JsonObject(emptyMap())))
        } catch (e: Exception) {
            Log.w(TAG, "command $command failed", e)
            Triple(false, e.message ?: e.javaClass.simpleName, JsonNull)
        }
    }

    private suspend fun currentPhotoJpeg(): ByteArray? = withContext(Dispatchers.IO) {
        val slide = g.slideshow.current.value ?: return@withContext null
        photoCache?.takeIf { it.first == slide.seq }?.let { return@withContext it.second }
        val src = slide.bitmap
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val scale = minOf(1f, 1280f / maxOf(soft.width, soft.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(soft, (soft.width * scale).toInt(), (soft.height * scale).toInt(), true) else soft
        val bytes = ByteArrayOutputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
        photoCache = slide.seq to bytes
        bytes
    }

    private fun ApplicationCall.authorized(): Boolean {
        val header = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val token = header ?: request.queryParameters["token"]
        return token != null && g.config.value.serverTokens.containsKey(token)
    }

    private suspend fun ApplicationCall.unauthorized() = json("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
    private suspend fun ApplicationCall.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respondText(body, ContentType.Application.Json, status)

    companion object { private const val TAG = "FrameServer" }
}

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
