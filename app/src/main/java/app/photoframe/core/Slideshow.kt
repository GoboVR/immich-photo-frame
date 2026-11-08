package app.photoframe.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import app.photoframe.immich.Album
import app.photoframe.immich.Asset
import app.photoframe.immich.ImmichClient
import app.photoframe.immich.Person
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

data class Slide(val asset: Asset, val bitmap: Bitmap, val seq: Long)

enum class SlideshowStatus { NOT_CONFIGURED, LOADING, READY, EMPTY, ERROR }

class SlideshowController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: ConfigStore,
    private val immich: ImmichClient,
    private val imageLoader: ImageLoader,
) {
    private val _current = MutableStateFlow<Slide?>(null)
    val current: StateFlow<Slide?> = _current

    private val _playing = MutableStateFlow(true)
    val playing: StateFlow<Boolean> = _playing

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums
    private val _people = MutableStateFlow<List<Person>>(emptyList())
    val people: StateFlow<List<Person>> = _people

    private val _status = MutableStateFlow(SlideshowStatus.NOT_CONFIGURED)
    val status: StateFlow<SlideshowStatus> = _status
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val photoChanged = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Set by the UI when a video slide finishes playing (its seq). */
    private val videoEndedSeq = MutableStateFlow(-1L)
    fun onVideoEnded(seq: Long) { videoEndedSeq.value = seq }

    /** While true the timer doesn't advance (e.g. a video is covering the photos). */
    var hold: () -> Boolean = { false }

    private val mutex = Mutex()
    private var queue = ArrayDeque<Asset>()
    private val history = ArrayDeque<Asset>()   // already shown, most recent last
    private var historyCursor = -1               // >=0 when browsing back through history
    private var preloaded: Pair<String, Bitmap>? = null
    private var poolDay: LocalDate? = null
    private var timerJob: Job? = null
    private var seq = 0L
    /** Bumped on manual navigation so the timer restarts its countdown. */
    private val tick = MutableStateFlow(0L)

    fun start() {
        scope.launch {
            // Rebuild the pool whenever source-related config changes.
            config.config.map { listOf(it.source, it.albumIds, it.personIds, it.immichUrl, it.immichApiKey, it.includeVideos, it.maxVideoSec) }
                .distinctUntilChanged()
                .collect { rebuild(advance = true) }
        }
        // Retry while we have nothing to show (e.g. Wi-Fi not up yet after boot).
        scope.launch {
            var wait = 5_000L
            while (true) {
                delay(wait)
                val st = _status.value
                if (_current.value == null && immich.configured && (st == SlideshowStatus.ERROR || st == SlideshowStatus.EMPTY)) {
                    rebuild(advance = false)
                    wait = (wait * 2).coerceAtMost(60_000)
                } else wait = 5_000L
            }
        }
        timerJob = scope.launch {
            while (true) {
                val startTick = tick.value
                // Re-evaluate every tick: the slide may change (and become a video) while we wait.
                var waited = 0L
                var seenSeq = _current.value?.seq
                while (tick.value == startTick) {
                    val cur = _current.value
                    if (cur?.seq != seenSeq) { seenSeq = cur?.seq; waited = 0 }
                    val isVideo = cur?.asset?.isVideo == true
                    // Videos stay until they finish (with a safety cap); photos use the interval.
                    if (isVideo && videoEndedSeq.value == cur?.seq) break
                    val limit = if (isVideo) (config.value.maxVideoSec + 15) * 1000L
                        else config.value.intervalSec.coerceIn(5, 3600) * 1000L
                    if (waited >= limit) break
                    delay(250); if (!hold()) waited += 250
                }
                if (tick.value == startTick && _playing.value && _status.value == SlideshowStatus.READY && !hold()) {
                    next(manual = false)
                } else {
                    delay(250) // paused / held: don't spin
                }
            }
        }
    }

    fun setPlaying(p: Boolean) { _playing.value = p; tick.value++ }

    suspend fun reloadSources() {
        if (!immich.configured) return
        runCatching { _albums.value = immich.albums() }.onFailure { Log.w(TAG, "albums", it) }
        runCatching { _people.value = immich.people() }.onFailure { Log.w(TAG, "people", it) }
    }

    suspend fun rebuild(advance: Boolean) {
        if (!immich.configured) { _status.value = SlideshowStatus.NOT_CONFIGURED; return }
        if (_current.value == null) _status.value = SlideshowStatus.LOADING
        try {
            reloadSources()
            val pool = buildPool()
            mutex.withLock {
                queue = ArrayDeque(pool)
                preloaded = null
                poolDay = LocalDate.now()
            }
            _error.value = null
            if (pool.isEmpty()) { _status.value = SlideshowStatus.EMPTY; return }
            if (advance || _current.value == null) next(manual = true)
        } catch (e: Exception) {
            Log.w(TAG, "rebuild failed", e)
            _error.value = e.message
            if (_current.value == null) _status.value = SlideshowStatus.ERROR
        }
    }

    private suspend fun buildPool(): List<Asset> {
        val cfg = config.value
        suspend fun albumPool(): List<Asset> {
            val ids = cfg.albumIds.ifEmpty { return emptyList() }
            return ids.flatMap { runCatching { immich.albumAssets(it) }.getOrDefault(emptyList()) }
        }
        suspend fun peoplePool(): List<Asset> =
            cfg.personIds.flatMap { runCatching { immich.personAssets(it) }.getOrDefault(emptyList()) }
        suspend fun memoryPool(): List<Asset> = runCatching { immich.memories() }.getOrDefault(emptyList())
        suspend fun randomPool(): List<Asset> = immich.random(100)

        val pool = when (cfg.source) {
            "album" -> albumPool().shuffled().ifEmpty { randomPool() }
            "people" -> peoplePool().shuffled().ifEmpty { randomPool() }
            "memories" -> memoryPool().shuffled().ifEmpty { randomPool() }
            "random" -> randomPool()
            else -> interleave(
                // Memories first-class on their day, weighted roughly 1:2:1:1
                memoryPool().shuffled().take(40),
                albumPool().shuffled(),
                peoplePool().shuffled(),
                runCatching { randomPool() }.getOrDefault(emptyList()),
                weights = intArrayOf(1, 2, 1, 1),
            )
        }
        return pool.distinctBy { it.id }.filter { a ->
            !a.isVideo || (cfg.includeVideos && (a.durationSec ?: 0.0) <= cfg.maxVideoSec)
        }
    }

    private fun interleave(vararg lists: List<Asset>, weights: IntArray): List<Asset> {
        val iters = lists.map { it.iterator() }
        val out = mutableListOf<Asset>()
        while (iters.any { it.hasNext() }) {
            iters.forEachIndexed { i, iter -> repeat(weights[i]) { if (iter.hasNext()) out += iter.next() } }
        }
        return out
    }

    suspend fun next(manual: Boolean = true) {
        if (manual) tick.value++
        val asset: Asset = mutex.withLock {
            if (historyCursor >= 0) {
                historyCursor++
                if (historyCursor < history.size) return@withLock history[historyCursor]
                historyCursor = -1
            }
            if (queue.isEmpty() || poolDay != LocalDate.now()) null else queue.removeFirst()
        } ?: run {
            // Exhausted (or new day): rebuild, which will call next() itself.
            rebuild(advance = false)
            mutex.withLock { if (queue.isEmpty()) null else queue.removeFirst() } ?: return
        }
        if (historyCursor < 0) {
            history.addLast(asset)
            while (history.size > 200) history.removeFirst()
        }
        show(asset)
    }

    suspend fun previous() {
        tick.value++
        val asset = mutex.withLock {
            if (history.size < 2) return
            historyCursor = if (historyCursor < 0) history.size - 2 else (historyCursor - 1).coerceAtLeast(0)
            history[historyCursor]
        }
        show(asset)
    }

    suspend fun showAsset(id: String): Boolean {
        tick.value++
        val asset = immich.asset(id) ?: return false
        history.addLast(asset)
        historyCursor = -1
        show(asset)
        return true
    }

    private suspend fun show(asset: Asset) {
        val bmp = preloaded?.takeIf { it.first == asset.id }?.second ?: load(asset.id)
        if (bmp == null) {
            // Skip broken assets, but don't loop forever.
            if (_current.value == null) _status.value = SlideshowStatus.ERROR
            return
        }
        _current.value = Slide(asset, bmp, ++seq)
        _status.value = SlideshowStatus.READY
        photoChanged.tryEmit(asset.id)
        // Preload the upcoming one.
        val upcoming = mutex.withLock {
            if (historyCursor >= 0 && historyCursor + 1 < history.size) history[historyCursor + 1] else queue.firstOrNull()
        }
        preloaded = upcoming?.let { a -> load(a.id)?.let { a.id to it } }
    }

    private suspend fun load(id: String): Bitmap? {
        val dm = context.resources.displayMetrics
        val longEdge = maxOf(dm.widthPixels, dm.heightPixels)
        val req = ImageRequest.Builder(context)
            .data(immich.imageUrl(id, config.value.fullResolution))
            .addHeader("x-api-key", immich.apiKey)
            .size(longEdge, longEdge)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .build()
        val res = imageLoader.execute(req)
        if (res !is SuccessResult) { Log.w(TAG, "load failed $id"); return null }
        return (res.drawable as? BitmapDrawable)?.bitmap
    }

    companion object { private const val TAG = "Slideshow" }
}
