package app.photoframe.assist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.photoframe.core.ConfigStore
import app.photoframe.ha.HaClient
import app.photoframe.media.MediaEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AssistPhase(val wire: String) {
    IDLE("idle"), WAKE_WORD("wake_word"), LISTENING("listening"), PROCESSING("processing"), RESPONDING("responding"), ERROR("idle")
}

data class AssistUi(
    val phase: AssistPhase = AssistPhase.IDLE,
    val transcript: String = "",
    val response: String = "",
    val error: String? = null,
    val level: Float = 0f,
    /** true while the overlay should be visible */
    val visible: Boolean = false,
)

data class AssistEvent(val name: String, val data: Map<String, String> = emptyMap())

class AssistController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: ConfigStore,
    private val ha: HaClient,
    private val media: MediaEngine,
    private val mic: AudioCapture,
) {
    private val _ui = MutableStateFlow(AssistUi())
    val ui: StateFlow<AssistUi> = _ui
    val events = MutableSharedFlow<AssistEvent>(extraBufferCapacity = 8)

    private var engine: WakeWordEngine? = null
    private var wakeListening = false
    private var runJob: Job? = null
    private var conversationId: String? = null

    private val wakeListener = AudioCapture.Listener { frame, _ ->
        val e = engine ?: return@Listener
        if (runJob?.isActive == true) return@Listener
        if (e.process(frame)) {
            events.tryEmit(AssistEvent("assist_wake"))
            scope.launch { start(fromWake = true) }
        }
    }

    private val hasMic get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startWakeWordWatcher() {
        scope.launch {
            config.config.map { Triple(it.wakeWordEnabled, it.wakeWord, it.wakeWordThreshold) }.distinctUntilChanged().collect { (enabled, word, th) ->
                applyWakeWord(enabled, word, th)
            }
        }
    }

    /** Re-check after mic permission is granted. */
    fun refreshWakeWord() { val c = config.value; applyWakeWord(c.wakeWordEnabled, c.wakeWord, c.wakeWordThreshold) }

    @Synchronized
    private fun applyWakeWord(enabled: Boolean, word: String, threshold: Float) {
        if (enabled && hasMic) {
            val e = engine ?: runCatching { WakeWordEngine(context) }.onFailure { Log.e(TAG, "wake word init", it) }.getOrNull() ?: return
            engine = e
            e.setModel(word)
            e.threshold = threshold
            if (!wakeListening) { mic.add(wakeListener); wakeListening = true }
            if (_ui.value.phase == AssistPhase.IDLE) _ui.value = _ui.value.copy(phase = AssistPhase.WAKE_WORD)
        } else {
            if (wakeListening) { mic.remove(wakeListener); wakeListening = false }
            if (_ui.value.phase == AssistPhase.WAKE_WORD) _ui.value = _ui.value.copy(phase = AssistPhase.IDLE)
        }
    }

    private fun idlePhase() = if (wakeListening) AssistPhase.WAKE_WORD else AssistPhase.IDLE

    fun cancel() {
        runJob?.cancel()
        _ui.value = AssistUi(phase = idlePhase())
    }

    fun dismiss() { if (runJob?.isActive != true) _ui.value = AssistUi(phase = idlePhase()) }

    fun start(fromWake: Boolean = false) {
        if (runJob?.isActive == true) { if (!fromWake) cancel(); return }
        runJob = scope.launch {
            try {
                var again = true
                conversationId = null
                while (again) again = runOnce()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "assist failed", e)
                showError(e.message ?: "Something went wrong")
            } finally {
                media.duck(1f)
            }
        }
    }

    private suspend fun showError(msg: String) {
        _ui.value = _ui.value.copy(phase = AssistPhase.ERROR, error = msg, visible = true)
        media.chime(false)
        delay(4000)
        if (_ui.value.phase == AssistPhase.ERROR) _ui.value = AssistUi(phase = idlePhase())
    }

    /** One pipeline run. Returns true if HA asked to continue the conversation. */
    private suspend fun runOnce(): Boolean {
        if (!hasMic) throw IllegalStateException("Microphone permission not granted")
        if (!ha.configured) throw IllegalStateException("Home Assistant isn't set up yet")
        ha.awaitConnected(8000)

        _ui.value = AssistUi(phase = AssistPhase.LISTENING, visible = true)
        media.duck(0.1f)
        media.chime(true)
        delay(200) // keep the chime out of the recording

        val handlerId = CompletableDeferred<Int>()
        val sttDone = CompletableDeferred<Unit>()
        val runDone = CompletableDeferred<Unit>()
        var ttsUrl: String? = null
        var continueConv = false
        var errorMsg: String? = null

        val sub = ha.subscribe(buildJsonObject {
            put("type", "assist_pipeline/run")
            put("start_stage", "stt")
            put("end_stage", "tts")
            putJsonObject("input") { put("sample_rate", AudioCapture.SAMPLE_RATE) }
            config.value.assistPipelineId?.let { put("pipeline", it) }
            conversationId?.let { put("conversation_id", it) }
            put("timeout", PIPELINE_TIMEOUT_S) // HA aborts the run after this
        }) { ev ->
            val type = ev.str("type")
            val data = ev["data"] as? JsonObject
            if (type != null && type !in setOf("stt-end", "intent-end", "tts-end")) Log.i(TAG, "pipeline: $type")
            when (type) {
                "run-start" -> {
                    val rd = data?.get("runner_data") as? JsonObject
                    val id = (rd?.get("stt_binary_handler_id") as? JsonPrimitive)?.intOrNull
                    if (id != null) handlerId.complete(id)
                }
                "stt-vad-end" -> sttDone.complete(Unit)
                "stt-end" -> {
                    sttDone.complete(Unit)
                    Log.i(TAG, "stt done: \"${(data?.get("stt_output") as? JsonObject)?.str("text").orEmpty()}\"")
                    val text = (data?.get("stt_output") as? JsonObject)?.str("text").orEmpty()
                    _ui.value = _ui.value.copy(phase = AssistPhase.PROCESSING, transcript = text, level = 0f)
                }
                "intent-end" -> {
                    Log.i(TAG, "intent done")
                    val out = data?.get("intent_output") as? JsonObject
                    out?.str("conversation_id")?.let { conversationId = it }
                    continueConv = (out?.get("continue_conversation") as? JsonPrimitive)?.contentOrNull == "true"
                    val speech = ((((out?.get("response") as? JsonObject)?.get("speech") as? JsonObject)
                        ?.get("plain") as? JsonObject)?.str("speech")).orEmpty()
                    _ui.value = _ui.value.copy(phase = AssistPhase.RESPONDING, response = speech)
                    events.tryEmit(AssistEvent("assist_result", mapOf("text" to _ui.value.transcript, "response" to speech)))
                }
                "tts-end" -> {
                    ttsUrl = (data?.get("tts_output") as? JsonObject)?.str("url")
                    Log.i(TAG, "tts ready")
                }
                "error" -> {
                    errorMsg = data?.str("message") ?: data?.str("code") ?: "Assist error"
                    sttDone.complete(Unit); runDone.complete(Unit)
                }
                "run-end" -> { sttDone.complete(Unit); runDone.complete(Unit) }
            }
        }

        try {
            val hid = withTimeoutOrNull(5000) { handlerId.await() } ?: throw IllegalStateException("Assist didn't start")
            val prefix = hid.toByte()
            val endpointer = Endpointer()
            var endReason = "ha-vad"
            val streamer = AudioCapture.Listener { frame, rms ->
                if (sttDone.isCompleted) return@Listener
                val bb = ByteBuffer.allocate(1 + frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                bb.put(prefix)
                for (s in frame) bb.putShort(s)
                ha.sendBinary(bb.array())
                _ui.value = _ui.value.copy(level = (rms * 12f).coerceIn(0f, 1f))
                // Local endpointing runs alongside HA's VAD; whichever ends first wins.
                when (endpointer.feed(rms)) {
                    Endpointer.Result.END_OF_SPEECH -> { endReason = "local-silence"; sttDone.complete(Unit) }
                    Endpointer.Result.NO_SPEECH -> { endReason = "no-speech"; sttDone.complete(Unit) }
                    Endpointer.Result.CONTINUE -> {}
                }
            }
            val listenStart = System.currentTimeMillis()
            mic.add(streamer)
            try {
                // Hard cap at 15 s in case neither detector fires.
                if (withTimeoutOrNull(15_000) { sttDone.await() } == null) endReason = "timeout"
            } finally {
                mic.remove(streamer)
                ha.sendBinary(byteArrayOf(prefix)) // end of audio
            }
            Log.i(TAG, "listening ended: reason=$endReason after ${System.currentTimeMillis() - listenStart}ms " +
                "speech=${endpointer.speechMs}ms floor=${"%.4f".format(endpointer.noiseFloor)} " +
                "threshold=${"%.4f".format(endpointer.threshold)} peak=${"%.4f".format(endpointer.peak)}")
            if (endReason == "no-speech") {
                // Nothing was said: don't wait for HA to fail, just stop quietly.
                ha.unsubscribeLocal(sub)
                media.duck(1f)
                _ui.value = AssistUi(phase = idlePhase())
                return false
            }
            if (_ui.value.phase == AssistPhase.LISTENING) _ui.value = _ui.value.copy(phase = AssistPhase.PROCESSING, level = 0f)
            // Slow answers (LLM agents, long scripts) are fine: keep waiting, but tell the user.
            val reassure = scope.launch {
                delay(15_000)
                val u = _ui.value
                if (u.phase == AssistPhase.PROCESSING && u.response.isBlank()) _ui.value = u.copy(response = "Still working on it…")
            }
            try {
                // Wait a little longer than HA's own pipeline timeout, so HA decides.
                if (withTimeoutOrNull((PIPELINE_TIMEOUT_S + 10) * 1000L) { runDone.await() } == null) {
                    Log.w(TAG, "Home Assistant didn't finish the pipeline within ${PIPELINE_TIMEOUT_S + 10} s")
                    if (errorMsg == null) errorMsg = "Home Assistant didn't answer"
                }
            } finally {
                reassure.cancel()
            }
        } finally {
            ha.unsubscribeLocal(sub)
        }

        errorMsg?.let { throw IllegalStateException(it) }
        media.duck(1f)
        ttsUrl?.let { media.announce(ha.absolute(it)) }
        if (continueConv) return true
        delay(2500)
        _ui.value = AssistUi(phase = idlePhase())
        return false
    }

    companion object {
        private const val TAG = "Assist"
        private const val PIPELINE_TIMEOUT_S = 90
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
