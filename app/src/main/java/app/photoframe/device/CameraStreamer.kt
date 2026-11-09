package app.photoframe.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Size
import android.view.Display
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class CameraFrame(val jpeg: ByteArray, val seq: Long)

/**
 * Live camera as a stream of JPEG frames (for MJPEG over HTTP).
 *
 * The camera only runs while at least one viewer holds a [Lease]; it stops
 * [IDLE_STOP_MS] after the last one leaves. [viewers] drives the on-screen
 * "camera is being viewed" indicator.
 */
class CameraStreamer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: ConfigStore,
) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val _frame = MutableStateFlow<CameraFrame?>(null)
    val frame: StateFlow<CameraFrame?> = _frame
    private val _viewers = MutableStateFlow(0)
    val viewers: StateFlow<Int> = _viewers

    private val seq = AtomicLong(0)
    private val analyzerThread = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var running = false
    private var stopJob: Job? = null
    @Volatile private var lastFrameAt = 0L

    val available: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    val hasPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val enabled: Boolean get() = config.value.cameraEnabled

    init {
        scope.launch(Dispatchers.Main) { registry.currentState = Lifecycle.State.CREATED }
        // Turning the camera off (or changing quality) takes effect immediately.
        scope.launch {
            config.config.map { Triple(it.cameraEnabled, it.cameraHd, it.cameraFps) }.distinctUntilChanged().collect { (on, _, _) ->
                withContext(Dispatchers.Main) {
                    if (!on) stopNow()
                    else if (running) { stopNow(); startIfNeeded() } // re-bind with new settings
                }
            }
        }
    }

    inner class Lease : Closeable {
        private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            val n = _viewers.updateAndGet { (it - 1).coerceAtLeast(0) }
            if (n == 0) {
                stopJob?.cancel()
                stopJob = scope.launch { delay(IDLE_STOP_MS); if (_viewers.value == 0) withContext(Dispatchers.Main) { stopNow() } }
            }
        }
    }

    /** Register a viewer and make sure the camera is running. */
    suspend fun acquire(): Lease {
        if (!enabled) throw IllegalStateException("camera is turned off on the frame")
        if (!hasPermission) throw IllegalStateException("camera permission not granted on the frame")
        _viewers.update { it + 1 }
        stopJob?.cancel()
        withContext(Dispatchers.Main) { startIfNeeded() }
        return Lease()
    }

    /** One fresh JPEG (starts the camera briefly if nobody is watching). */
    suspend fun snapshot(timeoutMs: Long = 6000): ByteArray? {
        val lease = acquire()
        try {
            val since = System.currentTimeMillis()
            return withTimeoutOrNull(timeoutMs) {
                // Wait for a frame captured after the request, not a stale one.
                frame.filterNotNull().first { lastFrameAt >= since }.jpeg
            }
        } finally {
            lease.close()
        }
    }

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
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        val minGapMs = 1000L / cfg.cameraFps.coerceIn(1, 30)
        analysis.setAnalyzer(analyzerThread) { image ->
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < minGapMs) return@setAnalyzer
                var bmp: Bitmap = image.toBitmap()
                val deg = image.imageInfo.rotationDegrees
                if (deg != 0) {
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
                }
                val out = ByteArrayOutputStream(64 * 1024)
                bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
                lastFrameAt = now
                _frame.value = CameraFrame(out.toByteArray(), seq.incrementAndGet())
            } catch (e: Exception) {
                Log.w(TAG, "frame failed", e)
            } finally {
                image.close()
            }
        }
        try {
            p.unbindAll()
            registry.currentState = Lifecycle.State.STARTED
            p.bindToLifecycle(this, selector, analysis)
            registry.currentState = Lifecycle.State.RESUMED
            running = true
            Log.i(TAG, "camera started (${target.width}x${target.height} @ ${cfg.cameraFps} fps)")
        } catch (e: Exception) {
            Log.w(TAG, "camera start failed", e)
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    private fun stopNow() {
        if (!running) return
        running = false
        provider?.unbindAll()
        registry.currentState = Lifecycle.State.CREATED
        _frame.value = null
        Log.i(TAG, "camera stopped")
    }

    companion object {
        private const val TAG = "CameraStreamer"
        private const val IDLE_STOP_MS = 15_000L
    }
}
