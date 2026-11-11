package app.photoframe.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class FrameConfig(
    val frameId: String = UUID.randomUUID().toString(),
    val frameName: String = "Photo Frame",
    // Immich
    val immichUrl: String = "",
    val immichApiKey: String = "",
    val fullResolution: Boolean = false,
    // Slideshow
    val source: String = "mixed", // album | people | memories | random | mixed
    val albumIds: List<String> = emptyList(),
    val personIds: List<String> = emptyList(),
    val intervalSec: Int = 30,
    val kenBurns: Boolean = true,
    /** Mix Immich videos into the slideshow (muted; tap for sound). */
    val includeVideos: Boolean = true,
    /** Skip videos longer than this. */
    val maxVideoSec: Int = 60,
    // Home Assistant
    val haUrl: String = "",
    val haToken: String = "",
    /** Extra candidate URLs pushed by the HA integration; tried after [haUrl]. */
    val haUrls: List<String> = emptyList(),
    /** Last URL that successfully connected (preferred on reconnect). */
    val haActiveUrl: String? = null,
    val assistPipelineId: String? = null,
    // Assist / wake word
    val wakeWordEnabled: Boolean = true,
    val wakeWord: String = "hey_jarvis",
    val wakeWordThreshold: Float = 0.5f,
    // Widgets
    val showClock: Boolean = true,
    val showWeather: Boolean = true,
    val showPhotoInfo: Boolean = true,
    val showControls: Boolean = true,
    val weatherEntity: String? = null,
    val controlEntities: List<String> = emptyList(),
    val showNowPlaying: Boolean = true,
    /** Other HA media players to show in the now-playing widget. */
    val nowPlayingEntities: List<String> = emptyList(),
    val use24h: Boolean = false,
    // Screen
    val brightness: Int = 200,
    val autoBrightness: Boolean = false,
    val nightMode: Boolean = false,
    val nightStart: String = "22:00",
    val nightEnd: String = "07:00",
    val autoNightMode: Boolean = false,
    // Live camera (front camera as MJPEG). Off by default.
    val cameraEnabled: Boolean = false,
    /** Read-only key for camera viewers (browser, Frigate, VLC); can't control the frame. */
    val cameraKey: String = randomKey(),
    val cameraHd: Boolean = false,
    /** Frames per second for the MJPEG (browser) stream; RTSP video runs at the camera's full rate. */
    val cameraFps: Int = 10,
    val rtspPort: Int = 8554,
    // Server
    val serverPort: Int = 8765,
    val serverTokens: Map<String, String> = emptyMap(), // token -> client name
)

fun randomKey(): String {
    val chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val rnd = java.security.SecureRandom()
    return (1..24).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
}

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("frame", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _config = MutableStateFlow(load())
    val config: StateFlow<FrameConfig> = _config
    val value: FrameConfig get() = _config.value

    private fun load(): FrameConfig {
        val raw = prefs.getString("config", null)
        val cfg = raw?.let { runCatching { json.decodeFromString<FrameConfig>(it) }.getOrNull() } ?: FrameConfig()
        if (raw == null) save(cfg)
        return cfg
    }

    private fun save(cfg: FrameConfig) {
        prefs.edit().putString("config", json.encodeToString(FrameConfig.serializer(), cfg)).apply()
    }

    fun update(block: (FrameConfig) -> FrameConfig) {
        _config.update { old -> block(old).also { if (it != old) save(it) } }
    }
}
