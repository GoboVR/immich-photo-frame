package app.photoframe.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Size
import android.view.Display
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.photoframe.core.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class CameraFrame(val jpeg: ByteArray, val seq: Long)

/**
 * The frame's live camera.
 *
 * One CameraX YUV stream (rotated natively) feeds two outputs, each only while needed:
 *  - H.264 (hardware encoder) for RTSP viewers: VLC, Frigate, Home Assistant
 *  - JPEG (native YuvImage compression) for MJPEG/snapshot viewers: browsers
 *
 * The camera runs while at least one viewer holds a [Lease] and stops [IDLE_STOP_MS] after the
 * last one leaves. [viewers] (all kinds) drives the on-screen "camera is being viewed" label.
 */
class CameraStreamer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: ConfigStore,
) : LifecycleOwner {
    enum class Kind { JPEG, H264 }

    fun interface H264Sink { fun onAccessUnit(data: ByteArray, ptsUs: Long, keyFrame: Boolean) }

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val _frame = MutableStateFlow<CameraFrame?>(null)
    val frame: StateFlow<CameraFrame?> = _frame
    private val _viewers = MutableStateFlow(0)
    val viewers: StateFlow<Int> = _viewers
    private val counts = MutableStateFlow(mapOf(Kind.JPEG to 0, Kind.H264 to 0))

    private val _h264Config = MutableStateFlow<H264Config?>(null)
    private val sinks = CopyOnWriteArraySet<H264Sink>()

    private val seq = AtomicLong(0)
    private val analyzerThread = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-frames") }
    private var provider: ProcessCameraProvider? = null
    private var running = false
    private var stopJob: Job? = null
    @Volatile private var lastJpegAt = 0L
    /** Only touched on [analyzerThread]. */
    private var encoder: H264Encoder? = null
    private var encoderFailedAt = 0L

    /** Latest SPS/PPS (for inserting before keyframes when the encoder doesn't). */
    val h264Config: H264Config? get() = _h264Config.value

    val available: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    val hasPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val enabled: Boolean get() = config.value.cameraEnabled

    init {
        scope.launch(Dispatchers.Main) { registry.currentState = Lifecycle.State.CREATED }
        // Turning the camera off (or changing quality) takes effect immediately.
        scope.launch {
            config.config.map { it.cameraEnabled to it.cameraHd }.distinctUntilChanged().collect { (on, _) ->
                withContext(Dispatchers.Main) {
                    if (!on) stopNow()
                    else if (running) { stopNow(); startIfNeeded() } // re-bind with the new resolution
                }
            }
        }
    }

    inner class Lease(private val kind: Kind) : Closeable {
        private var closed = false
        override fun close() {
            synchronized(this) { if (closed) return; closed = true }
            counts.update { it + (kind to ((it[kind] ?: 1) - 1).coerceAtLeast(0)) }
            val n = counts.value.values.sum()
            _viewers.value = n
            if (n == 0) {
                stopJob?.cancel()
                stopJob = scope.launch { delay(IDLE_STOP_MS); if (_viewers.value == 0) withContext(Dispatchers.Main) { stopNow() } }
            }
        }
    }

    /** Register a viewer of the given kind and make sure the camera is running. */
    suspend fun acquire(kind: Kind = Kind.JPEG): Lease {
        if (!enabled) throw IllegalStateException("camera is turned off on the frame")
        if (!hasPermission) throw IllegalStateException("camera permission not granted on the frame")
        counts.update { it + (kind to (it[kind] ?: 0) + 1) }
        _viewers.value = counts.value.values.sum()
        stopJob?.cancel()
        withContext(Dispatchers.Main) { startIfNeeded() }
        return Lease(kind)
    }

    private fun wanted(kind: Kind) = (counts.value[kind] ?: 0) > 0

    /** One fresh JPEG (starts the camera briefly if nobody is watching). */
    suspend fun snapshot(timeoutMs: Long = 6000): ByteArray? {
        val lease = acquire(Kind.JPEG)
        try {
            val since = System.currentTimeMillis()
            return withTimeoutOrNull(timeoutMs) {
                // Wait for a frame captured after the request, not a stale one.
                frame.filterNotNull().first { lastJpegAt >= since }.jpeg
            }
        } finally {
            lease.close()
        }
    }

    // ------------------------------------------------------------------ H.264 for RTSP

    fun addSink(sink: H264Sink) { sinks += sink }
    fun removeSink(sink: H264Sink) { sinks -= sink }

    /** SPS/PPS of the running encoder (waits for it to start). Needs an H264 lease. */
    suspend fun awaitH264Config(timeoutMs: Long = 8000): H264Config? =
        withTimeoutOrNull(timeoutMs) { _h264Config.filterNotNull().first() }

    fun requestKeyFrame() { analyzerThread.execute { encoder?.requestKeyFrame() } }

    private fun ensureEncoder(width: Int, height: Int) {
        val e = encoder
        if (e != null && e.width == width && e.height == height) return
        if (e == null && System.currentTimeMillis() - encoderFailedAt < 5_000) return // don't retry every frame
        closeEncoder()
        val hd = width * height > 640 * 480
        encoder = try {
            H264Encoder(
                width, height, fps = 30, bitrate = if (hd) 3_000_000 else 1_500_000,
                onConfig = { _h264Config.value = it },
                onAccessUnit = { data, pts, key -> for (s in sinks) s.onAccessUnit(data, pts, key) },
            )
        } catch (ex: Exception) {
            Log.e(TAG, "H.264 encoder failed to start", ex)
            encoderFailedAt = System.currentTimeMillis()
            null
        }
    }

    private fun closeEncoder() {
        encoder?.close()
        encoder = null
        _h264Config.value = null
    }

    // ------------------------------------------------------------------ camera

    private suspend fun startIfNeeded() {
        if (running || !enabled || !hasPermission) return
        val p = provider ?: withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }.also { provider = it }
        val selector = when {
            p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> { Log.w(TAG, "no camera found"); return }
        }
        val cfg = config.value
        val target = if (cfg.cameraHd) Size(1280, 720) else Size(640, 480)
        val rotation = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
            )
            .setTargetRotation(rotation)
            // Rotate upright in native code, so both outputs get correctly oriented frames.
            .setOutputImageRotationEnabled(true)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(analyzerThread) { image -> onFrame(image) }
        try {
            p.unbindAll()
            registry.currentState = Lifecycle.State.STARTED
            p.bindToLifecycle(this, selector, analysis)
            registry.currentState = Lifecycle.State.RESUMED
            running = true
            Log.i(TAG, "camera started (target ${target.width}x${target.height})")
        } catch (e: Exception) {
            Log.w(TAG, "camera start failed", e)
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    /** Runs on [analyzerThread] for every camera frame. */
    private fun onFrame(image: ImageProxy) {
        try {
            // Even sizes keep YUV 4:2:0 and the encoder happy.
            val w = image.width and 1.inv(); val h = image.height and 1.inv()
            if (wanted(Kind.H264)) {
                ensureEncoder(w, h)
                encoder?.encode(image, image.imageInfo.timestamp / 1000)
            } else if (encoder != null) {
                closeEncoder()
            }
            if (wanted(Kind.JPEG)) {
                val now = System.currentTimeMillis()
                val gap = 1000L / config.value.cameraFps.coerceIn(1, 30)
                if (now - lastJpegAt >= gap) {
                    val nv21 = toNv21(image, w, h)
                    val out = ByteArrayOutputStream(64 * 1024)
                    YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 75, out)
                    lastJpegAt = now
                    _frame.value = CameraFrame(out.toByteArray(), seq.incrementAndGet())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame failed", e)
            if (e is IllegalStateException || e is android.media.MediaCodec.CodecException) closeEncoder()
        } finally {
            image.close()
        }
    }

    private fun stopNow() {
        if (!running) return
        running = false
        provider?.unbindAll()
        registry.currentState = Lifecycle.State.CREATED
        _frame.value = null
        analyzerThread.execute { closeEncoder() }
        Log.i(TAG, "camera stopped")
    }

    companion object {
        private const val TAG = "CameraStreamer"
        private const val IDLE_STOP_MS = 15_000L

        /** YUV_420_888 (any strides) → NV21 (Y plane, then interleaved V/U) for YuvImage. */
        fun toNv21(image: ImageProxy, w: Int, h: Int): ByteArray {
            val out = ByteArray(w * h * 3 / 2)
            val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
            val yb = y.buffer.duplicate()
            if (y.pixelStride == 1) {
                for (row in 0 until h) { yb.position(row * y.rowStride); yb.get(out, row * w, w) }
            } else {
                var o = 0
                for (row in 0 until h) for (col in 0 until w) out[o++] = yb.get(row * y.rowStride + col * y.pixelStride)
            }
            val ub = u.buffer.duplicate(); val vb = v.buffer.duplicate()
            var o = w * h
            for (row in 0 until h / 2) {
                val uo = row * u.rowStride; val vo = row * v.rowStride
                for (col in 0 until w / 2) {
                    out[o++] = vb.get(vo + col * v.pixelStride)
                    out[o++] = ub.get(uo + col * u.pixelStride)
                }
            }
            return out
        }
    }
}
