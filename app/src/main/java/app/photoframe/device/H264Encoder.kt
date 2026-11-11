package app.photoframe.device

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/** SPS/PPS without start codes (needed for the RTSP SDP). */
class H264Config(val sps: ByteArray, val pps: ByteArray)

/**
 * Hardware H.264 encoder fed with YUV frames from CameraX.
 * [encode] runs on the caller's (camera) thread; output is drained on its own thread and
 * delivered as Annex-B access units (start-code separated NALs) to [onAccessUnit].
 */
class H264Encoder(
    val width: Int,
    val height: Int,
    fps: Int,
    bitrate: Int,
    private val onConfig: (H264Config) -> Unit,
    private val onAccessUnit: (data: ByteArray, ptsUs: Long, keyFrame: Boolean) -> Unit,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    @Volatile private var running = true
    private val drainThread: Thread

    init {
        fun format(prependHeaders: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // viewers can join within ~1 s
            // Repeat SPS/PPS before every keyframe so late joiners can decode. Not every encoder
            // supports it; without it the RTSP server inserts them itself.
            if (prependHeaders) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        try {
            codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "encoder doesn't support repeating SPS/PPS; the server will add them")
            codec.reset()
            codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        codec.start()
        drainThread = Thread({ drain() }, "h264-drain").apply { start() }
        Log.i(TAG, "encoder started ${width}x$height @ $fps fps, ${bitrate / 1000} kbps (${codec.name})")
    }

    /** Copy one camera frame into the encoder. Drops the frame if the encoder is busy. */
    fun encode(image: ImageProxy, ptsUs: Long) {
        val index = codec.dequeueInputBuffer(2_000)
        if (index < 0) return
        val input = codec.getInputImage(index)
        if (input == null) { codec.queueInputBuffer(index, 0, 0, ptsUs, 0); return }
        copyYuv(image, input, width, height)
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
    }

    fun requestKeyFrame() {
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        val sps = fmt.getByteBuffer("csd-0")?.toArray()?.let(::stripStartCode)
                        val pps = fmt.getByteBuffer("csd-1")?.toArray()?.let(::stripStartCode)
                        if (sps != null && pps != null) onConfig(H264Config(sps, pps))
                    }
                    index >= 0 -> {
                        val buf = codec.getOutputBuffer(index)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val data = ByteArray(info.size).also { buf.get(it) }
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                configFrom(data)?.let(onConfig)
                            } else {
                                onAccessUnit(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "drain failed", e)
        }
    }

    fun close() {
        running = false
        runCatching { drainThread.join(500) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        Log.i(TAG, "encoder stopped")
    }

    private fun configFrom(annexB: ByteArray): H264Config? {
        val nals = splitNals(annexB)
        val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        return if (sps != null && pps != null) H264Config(sps, pps) else null
    }

    companion object {
        private const val TAG = "H264Encoder"

        private fun ByteBuffer.toArray(): ByteArray { val d = duplicate(); d.rewind(); return ByteArray(d.remaining()).also { d.get(it) } }

        private fun stripStartCode(b: ByteArray): ByteArray = splitNals(b).firstOrNull() ?: b

        /** Split an Annex-B byte stream into NAL units (without start codes). */
        fun splitNals(b: ByteArray): List<ByteArray> {
            val starts = ArrayList<Int>()   // index of first byte after each start code
            var i = 0
            while (i + 3 <= b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) { starts += i + 3; i += 3 }
                else i++
            }
            if (starts.isEmpty()) return if (b.isEmpty()) emptyList() else listOf(b)
            val out = ArrayList<ByteArray>(starts.size)
            for ((n, s) in starts.withIndex()) {
                var e = if (n + 1 < starts.size) starts[n + 1] - 3 else b.size
                // a 4-byte start code (00 00 00 01) leaves a trailing zero on the previous NAL
                while (n + 1 < starts.size && e > s && b[e - 1].toInt() == 0) e--
                if (e > s) out += b.copyOfRange(s, e)
            }
            return out
        }

        /** Copy YUV_420_888 planes honouring each side's row/pixel strides. */
        fun copyYuv(src: ImageProxy, dst: Image, width: Int, height: Int) {
            for (p in 0 until 3) {
                val s = src.planes[p]; val d = dst.planes[p]
                val w = if (p == 0) width else width / 2
                val h = if (p == 0) height else height / 2
                val sb = s.buffer.duplicate(); val db = d.buffer.duplicate()
                if (s.pixelStride == 1 && d.pixelStride == 1) {
                    val row = ByteArray(w)
                    for (y in 0 until h) {
                        sb.position(y * s.rowStride); sb.get(row, 0, w)
                        db.position(y * d.rowStride); db.put(row, 0, w)
                    }
                } else {
                    for (y in 0 until h) {
                        val so = y * s.rowStride; val dOff = y * d.rowStride
                        for (x in 0 until w) db.put(dOff + x * d.pixelStride, sb.get(so + x * s.pixelStride))
                    }
                }
            }
        }
    }
}
