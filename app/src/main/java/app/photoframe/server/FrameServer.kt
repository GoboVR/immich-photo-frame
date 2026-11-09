package app.photoframe.server

import android.graphics.Bitmap
import android.util.Log
import app.photoframe.AppGraph
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import java.security.MessageDigest
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

                    // ---------------- live camera (HA token or read-only camera key)
                    get("/api/v1/camera.jpg") {
                        if (!call.cameraAuthorized()) return@get call.unauthorized()
                        if (!g.camera.enabled) return@get call.json("""{"error":"camera_off"}""", HttpStatusCode.ServiceUnavailable)
                        val jpeg = runCatching { g.camera.snapshot() }.getOrElse {
                            return@get call.json(buildJsonObject { put("error", it.message ?: "camera_error") }.toString(), HttpStatusCode.ServiceUnavailable)
                        } ?: return@get call.json("""{"error":"no_frame"}""", HttpStatusCode.ServiceUnavailable)
                        call.response.header(HttpHeaders.CacheControl, "no-store")
                        call.respondBytes(jpeg, ContentType.Image.JPEG)
                    }

                    get("/api/v1/camera.mjpeg") {
                        if (!call.cameraAuthorized()) return@get call.unauthorized()
                        val lease = runCatching { g.camera.acquire() }.getOrElse {
                            return@get call.json(buildJsonObject { put("error", it.message ?: "camera_error") }.toString(), HttpStatusCode.ServiceUnavailable)
                        }
                        try {
                            call.response.header(HttpHeaders.CacheControl, "no-store")
                            call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY")) {
                                var last = -1L
                                // Ends when the camera is turned off or the viewer disconnects (write throws).
                                while (g.camera.enabled) {
                                    val f = g.camera.frame.value
                                    if (f == null || f.seq == last) { delay(15); continue }
                                    last = f.seq
                                    writeFully("--$MJPEG_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.jpeg.size}\r\n\r\n".toByteArray())
                                    writeFully(f.jpeg)
                                    writeFully("\r\n".toByteArray())
                                    flush()
                                }
                            }
                        } finally {
                            lease.close()
                        }
                    }

                    // Simple viewer page: http://<frame>:8765/camera?key=<camera key>
                    get("/camera") {
                        val key = call.request.queryParameters["key"].orEmpty()
                        if (!call.cameraAuthorized() || !key.matches(Regex("[A-Za-z0-9]+"))) {
                            return@get call.respondText(CAMERA_LOGIN_HTML, ContentType.Text.Html, HttpStatusCode.Unauthorized)
                        }
                        call.respondText(cameraPageHtml(g.config.value.frameName, key), ContentType.Text.Html)
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

    /** HA pairing tokens can do everything; the camera key can only watch the camera. */
    private fun ApplicationCall.cameraAuthorized(): Boolean {
        if (authorized()) return true
        val key = request.queryParameters["key"] ?: request.headers["X-Camera-Key"] ?: return false
        val expected = g.config.value.cameraKey
        return MessageDigest.isEqual(key.toByteArray(), expected.toByteArray())
    }

    private fun ApplicationCall.authorized(): Boolean {
        val header = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val token = header ?: request.queryParameters["token"]
        return token != null && g.config.value.serverTokens.containsKey(token)
    }

    private suspend fun ApplicationCall.unauthorized() = json("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
    private suspend fun ApplicationCall.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respondText(body, ContentType.Application.Json, status)

    companion object {
        private const val TAG = "FrameServer"
        private const val MJPEG_BOUNDARY = "photoframe"

        private const val CAMERA_LOGIN_HTML = """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Photo Frame camera</title><body style="font-family:system-ui;background:#111;color:#eee;display:grid;place-items:center;height:100vh;margin:0">
<form method=get action=/camera style="display:grid;gap:12px;text-align:center"><h2 style=margin:0>Photo Frame camera</h2>
<p style=margin:0;opacity:.7>Enter the camera key shown in the frame's settings.</p>
<input name=key autocomplete=off placeholder="Camera key" style="padding:10px;font-size:16px;border-radius:8px;border:1px solid #444;background:#222;color:#eee">
<button style="padding:10px;font-size:16px;border-radius:8px;border:0;background:#4a7dff;color:#fff">View</button></form></body>"""

        fun cameraPageHtml(name: String, key: String): String {
            val title = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>$title · camera</title><body style="margin:0;background:#000;height:100vh;display:grid;place-items:center">
<img src="/api/v1/camera.mjpeg?key=$key" alt="Live camera" style="max-width:100vw;max-height:100vh"></body>"""
        }
    }
}

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
