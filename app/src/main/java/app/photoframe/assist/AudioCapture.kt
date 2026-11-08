package app.photoframe.assist

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.sqrt

/**
 * Single shared microphone at 16 kHz mono PCM16, delivering 80 ms (1280 sample) frames
 * to any number of listeners. Started while at least one listener is registered.
 */
class AudioCapture {
    fun interface Listener { fun onFrame(frame: ShortArray, rms: Float) }

    private val listeners = CopyOnWriteArraySet<Listener>()
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var generation = 0

    @Synchronized
    fun add(l: Listener) {
        listeners += l
        if (!running) start()
    }

    @Synchronized
    fun remove(l: Listener) {
        listeners -= l
        if (listeners.isEmpty()) stop()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        running = true
        val gen = ++generation
        thread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 2 * 4))
            } catch (e: Exception) { Log.e(TAG, "AudioRecord failed", e); running = false; return@Thread }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized (mic permission?)"); rec.release(); running = false; return@Thread
            }
            val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
            val agc = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } else null
            rec.startRecording()
            val buf = ShortArray(FRAME)
            try {
                while (running && gen == generation) {
                    var off = 0
                    while (off < FRAME && running) {
                        val n = rec.read(buf, off, FRAME - off)
                        if (n < 0) { Log.w(TAG, "read error $n"); Thread.sleep(100); break }
                        off += n
                    }
                    if (off < FRAME) continue
                    var sum = 0.0
                    for (s in buf) sum += s.toDouble() * s
                    val rms = (sqrt(sum / FRAME) / 32768.0).toFloat()
                    val copy = buf.copyOf()
                    for (l in listeners) runCatching { l.onFrame(copy, rms) }.onFailure { Log.w(TAG, "listener", it) }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release(); ns?.release(); agc?.release()
            }
        }, "frame-mic").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun stop() {
        running = false
        thread = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = 1280 // 80 ms
        private const val TAG = "AudioCapture"
    }
}
