package app.photoframe.media

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import android.view.TextureView
import kotlinx.coroutines.Job
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.math.roundToInt

data class MediaState(
    val state: String = "idle", // idle | playing | paused | buffering
    val url: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val albumArt: String? = null,
    val duration: Double? = null,
    val position: Double? = null,
    val positionUpdatedAt: String? = null,
    val volume: Float = 0.5f,
    val muted: Boolean = false,
    /** audio | video | image */
    val content: String = "audio",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** For images: re-fetch every N seconds (live camera snapshots). */
    val refreshSec: Double? = null,
) {
    val visual: Boolean get() = url != null && (content == "video" || content == "image")
}

/** Decide what HA sent us. Explicit mime wins, then HA's media type, then the URL. */
fun mediaKind(url: String, mime: String?, mediaType: String?): String {
    val m = mime?.lowercase().orEmpty()
    when {
        m.startsWith("video/") || m.contains("mpegurl") || m.contains("dash+xml") -> return "video"
        m.startsWith("image/") -> return "image"
        m.startsWith("audio/") -> return "audio"
    }
    when (mediaType?.lowercase()?.substringBefore('/')) {
        "video", "movie", "tvshow", "episode", "channel", "season" -> return "video"
        "image", "photo" -> return "image"
        "music", "track", "album", "playlist", "podcast", "audio", "radio" -> return "audio"
    }
    val path = url.substringBefore('?').lowercase()
    return when {
        Regex("\\.(mp4|m4v|mkv|webm|mov|avi|ts|m3u8|mpd)$").containsMatchIn(path) -> "video"
        Regex("\\.(jpe?g|png|gif|webp|bmp|heic)$").containsMatchIn(path) -> "image"
        path.contains("/api/camera_proxy/") -> "image"
        path.contains("/api/hls/") -> "video"
        else -> "audio"
    }
}


/** Music/media playback + announcements (TTS). All ExoPlayer calls are marshalled to the main thread. */
class MediaEngine(private val context: Context, private val scope: CoroutineScope) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private lateinit var music: ExoPlayer
    private lateinit var announcer: ExoPlayer
    private var meta = MediaState()
    private var announcing = 0

    private val _state = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = _state
    val ended = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    fun start() {
        scope.launch(Dispatchers.Main) {
            val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
            music = ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(attrs, false)
                addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = publish()
                    override fun onVideoSizeChanged(size: VideoSize) {
                        if (size.width > 0 && size.height > 0) {
                            meta = meta.copy(videoWidth = (size.width * size.pixelWidthHeightRatio).toInt(), videoHeight = size.height)
                            publish()
                        }
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        // e.g. an HLS radio stream we guessed was video: show it as audio instead.
                        if (meta.content == "video" && !tracks.isEmpty && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) {
                            meta = meta.copy(content = "audio"); publish()
                        }
                    }
                    override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_ENDED) { meta = MediaState(); ended.tryEmit(Unit) } }
                    override fun onPlayerError(error: PlaybackException) { meta = MediaState(); publish() }
                })
            }
            announcer = ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_ASSISTANT).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
            }
            // Poll position + system volume (volume keys / other apps can change it).
            while (true) { publish(); delay(1000) }
        }
    }

    private fun publish() {
        if (!::music.isInitialized) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val st = when {
            meta.url == null -> "idle"
            meta.content == "image" -> "playing"
            music.playbackState == Player.STATE_BUFFERING -> "buffering"
            music.isPlaying -> "playing"
            music.playbackState == Player.STATE_IDLE || music.playbackState == Player.STATE_ENDED -> "idle"
            else -> "paused"
        }
        val isImage = meta.content == "image"
        val dur = if (isImage) null else music.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000.0)
        val next = meta.copy(
            state = st, volume = vol, muted = muted, duration = dur,
            position = if (meta.url != null && !isImage) music.currentPosition / 1000.0 else null,
        )
        val prev = _state.value
        // HA semantics: `position` is where playback was AT `position_updated_at`; readers
        // extrapolate while playing. So only publish a new (position, stamp) pair when the
        // real position drifts from that extrapolation (seek, buffering) or the state changes;
        // otherwise keep the previous pair so the state doesn't change every second.
        val now = Instant.now()
        val prevStamp = prev.positionUpdatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val expected = prev.position?.let { p ->
            if (prev.state == "playing" && prevStamp != null) p + (now.toEpochMilli() - prevStamp.toEpochMilli()) / 1000.0 else p
        }
        val fresh = prev.state != next.state || prev.url != next.url || prevStamp == null || expected == null ||
            next.position == null || kotlin.math.abs(expected - next.position) > 1.5
        _state.value = when {
            next.url == null || next.position == null -> next.copy(positionUpdatedAt = null)
            fresh -> next.copy(positionUpdatedAt = now.toString())
            else -> next.copy(position = prev.position, positionUpdatedAt = prev.positionUpdatedAt)
        }
    }

    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private var autoStop: Job? = null

    suspend fun play(
        url: String, title: String?, artist: String?, albumArt: String?, mime: String?,
        mediaType: String? = null, durationSec: Double? = null, refreshSec: Double? = null,
    ) = main {
        autoStop?.cancel()
        val kind = mediaKind(url, mime, mediaType)
        meta = MediaState(url = url, title = title, artist = artist, albumArt = albumArt, content = kind,
            refreshSec = refreshSec?.takeIf { kind == "image" })
        if (kind == "image") {
            // Images don't need the player; stop whatever was playing.
            music.stop(); music.clearMediaItems()
        } else {
            val item = MediaItem.Builder().setUri(Uri.parse(url)).apply {
                when (mime?.lowercase()) {
                    // HA reports HLS as vnd.apple.mpegurl; media3 expects x-mpegURL.
                    "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    null, "" -> {}
                    else -> setMimeType(mime)
                }
                setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
            }.build()
            music.setMediaItem(item)
            music.prepare()
            music.play()
        }
        // Images default to 30 s on screen; videos/streams play until they end or are stopped.
        val limit = durationSec ?: if (kind == "image") 30.0 else null
        if (limit != null) {
            autoStop = scope.launch(Dispatchers.Main) {
                delay((limit * 1000).toLong())
                if (meta.url == url) { stop(); ended.tryEmit(Unit) }
            }
        }
        publish()
    }

    /** Attach/detach the on-screen surface for video. Main thread only. */
    fun setVideoSurface(view: TextureView?) {
        if (!::music.isInitialized) return
        if (view != null) music.setVideoTextureView(view) else music.clearVideoSurface()
    }

    suspend fun pause() = main { music.pause(); publish() }
    suspend fun resume() = main { music.play(); publish() }
    suspend fun stop() = main { autoStop?.cancel(); music.stop(); music.clearMediaItems(); meta = MediaState(); publish() }
    suspend fun seek(seconds: Double) = main { music.seekTo((seconds * 1000).toLong()); publish() }

    fun setVolume(v: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).roundToInt(), 0)
        scope.launch(Dispatchers.Main) { publish() }
    }

    fun setMuted(m: Boolean) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (m) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        scope.launch(Dispatchers.Main) { publish() }
    }

    /** Duck music to [level] (1 = normal). Used during Assist listening. */
    suspend fun duck(level: Float) = main { if (::music.isInitialized) music.volume = level }

    /**
     * Play an announcement (TTS, doorbell…) over the music, ducking it, and suspend until
     * finished (or 2 min timeout).
     */
    suspend fun announce(url: String) {
        val done = CompletableDeferred<Unit>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_ENDED) done.complete(Unit) }
            override fun onPlayerError(error: PlaybackException) { done.complete(Unit) }
        }
        main {
            announcing++
            music.volume = 0.15f
            announcer.addListener(listener)
            announcer.setMediaItem(MediaItem.fromUri(url))
            announcer.prepare()
            announcer.play()
        }
        try {
            withTimeoutOrNull(120_000) { done.await() }
        } finally {
            main {
                announcer.removeListener(listener)
                if (--announcing == 0) { music.volume = 1f; announcer.stop() }
            }
        }
    }

    fun chime(listening: Boolean) {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tg.startTone(if (listening) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_PROMPT, 150)
            scope.launch { delay(400); tg.release() }
        }
    }
}
