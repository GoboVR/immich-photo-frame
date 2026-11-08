package app.photoframe.assist

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * On-device openWakeWord (https://github.com/dscripka/openWakeWord) using ONNX Runtime.
 *
 * Pipeline per 80 ms frame (1280 samples @16 kHz):
 *   raw PCM (+480 samples context) → melspectrogram.onnx → 8 mel frames (x/10+2)
 *   last 76 mel frames → embedding_model.onnx → one 96-d embedding
 *   last 16 embeddings → <wakeword>.onnx → score 0..1
 */
class WakeWordEngine(private val context: Context) {
    companion object {
        private const val TAG = "WakeWord"
        private const val MEL_CONTEXT = 160 * 3
        private const val MEL_WINDOW = 76
        private const val EMB_WINDOW = 16
        private const val COOLDOWN_FRAMES = 25 // 2 s

        /** Asset file name → user-facing id. */
        val MODELS = linkedMapOf(
            "hey_jarvis" to "hey_jarvis_v0.1.onnx",
            "alexa" to "alexa_v0.1.onnx",
            "hey_mycroft" to "hey_mycroft_v0.1.onnx",
            "ok_nabu" to "ok_nabu.onnx",
        )
    }

    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1); setInterOpNumThreads(1) }
    private val mel: OrtSession = session("melspectrogram.onnx")
    private val emb: OrtSession = session("embedding_model.onnx")
    private var ww: OrtSession? = null
    var currentModel: String? = null; private set

    private val raw = FloatArray(AudioCapture.FRAME + MEL_CONTEXT)
    private val melBuf = ArrayDeque<FloatArray>()     // each 32 floats
    private val embBuf = ArrayDeque<FloatArray>()     // each 96 floats
    private var cooldown = 0
    private var warmup = 5

    var threshold = 0.5f
    var lastScore = 0f; private set

    init { reset() }

    private fun session(name: String): OrtSession =
        env.createSession(context.assets.open("wakeword/$name").use { it.readBytes() }, opts)

    @Synchronized
    fun setModel(id: String) {
        if (id == currentModel) return
        val file = MODELS[id] ?: MODELS.values.first()
        ww?.close()
        ww = session(file)
        currentModel = if (MODELS.containsKey(id)) id else MODELS.keys.first()
        reset()
    }

    @Synchronized
    fun reset() {
        raw.fill(0f)
        melBuf.clear(); repeat(MEL_WINDOW) { melBuf.addLast(FloatArray(32) { 1f }) }
        embBuf.clear()
        warmup = 5
    }

    /** Feed one 1280-sample frame. Returns true if the wake word fired. */
    @Synchronized
    fun process(frame: ShortArray): Boolean {
        val model = ww ?: return false
        // Slide raw buffer: keep the last MEL_CONTEXT samples, append new frame.
        System.arraycopy(raw, AudioCapture.FRAME, raw, 0, MEL_CONTEXT)
        for (i in frame.indices) raw[MEL_CONTEXT + i] = frame[i].toFloat()

        // 1. mel spectrogram
        OnnxTensor.createTensor(env, FloatBuffer.wrap(raw), longArrayOf(1, raw.size.toLong())).use { t ->
            mel.run(mapOf(mel.inputNames.first() to t)).use { out ->
                val v = out[0].value
                @Suppress("UNCHECKED_CAST")
                val frames = (v as Array<Array<Array<FloatArray>>>)[0][0]
                for (f in frames) {
                    melBuf.addLast(FloatArray(32) { f[it] / 10f + 2f })
                }
            }
        }
        while (melBuf.size > MEL_WINDOW) melBuf.removeFirst()

        // 2. embedding of last 76 mel frames → [1,76,32,1]
        val melFlat = FloatArray(MEL_WINDOW * 32)
        melBuf.forEachIndexed { i, f -> System.arraycopy(f, 0, melFlat, i * 32, 32) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(melFlat), longArrayOf(1, MEL_WINDOW.toLong(), 32, 1)).use { t ->
            emb.run(mapOf(emb.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val e = (out[0].value as Array<Array<Array<FloatArray>>>)[0][0][0]
                embBuf.addLast(e.copyOf())
            }
        }
        while (embBuf.size > EMB_WINDOW) embBuf.removeFirst()
        if (embBuf.size < EMB_WINDOW) return false

        // 3. wake word classifier → [1,16,96]
        val embFlat = FloatArray(EMB_WINDOW * 96)
        embBuf.forEachIndexed { i, e -> System.arraycopy(e, 0, embFlat, i * 96, 96) }
        val score = OnnxTensor.createTensor(env, FloatBuffer.wrap(embFlat), longArrayOf(1, EMB_WINDOW.toLong(), 96)).use { t ->
            model.run(mapOf(model.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                (out[0].value as Array<FloatArray>)[0][0]
            }
        }
        lastScore = score
        if (warmup > 0) { warmup--; return false }
        if (cooldown > 0) { cooldown--; return false }
        if (score >= threshold) {
            Log.i(TAG, "wake word '$currentModel' score=$score")
            cooldown = COOLDOWN_FRAMES
            embBuf.clear()
            return true
        }
        return false
    }

    fun close() { ww?.close(); mel.close(); emb.close() }
}
