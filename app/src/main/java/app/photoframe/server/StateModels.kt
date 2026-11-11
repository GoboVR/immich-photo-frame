package app.photoframe.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WireState(
    val id: String,
    val name: String,
    val screen: WireScreen,
    val slideshow: WireSlideshow,
    val sources: WireSources,
    val media: WireMedia,
    val assist: WireAssist,
    val device: WireDevice,
    val widgets: WireWidgets,
    val ha: WireHa,
    val camera: WireCamera? = null,
)

@Serializable data class WireCamera(
    /** Turned on in the frame's settings (or from HA). */
    val enabled: Boolean,
    /** The tablet has a camera and the app may use it. */
    val available: Boolean,
    /** Someone is watching right now (camera running). */
    val streaming: Boolean,
    val viewers: Int,
    /** H.264 RTSP stream: rtsp://<frame>:<rtsp_port>/camera?token=<HA token> */
    @SerialName("rtsp_port") val rtspPort: Int? = null,
)

@Serializable data class WireScreen(
    val on: Boolean, val brightness: Int,
    @SerialName("auto_brightness") val autoBrightness: Boolean,
    @SerialName("night_mode") val nightMode: Boolean,
)

@Serializable data class WireSlideshow(
    val playing: Boolean, val interval: Int, val source: String,
    @SerialName("album_ids") val albumIds: List<String>,
    @SerialName("person_ids") val personIds: List<String>,
    val current: WireCurrent?,
)

@Serializable data class WireCurrent(
    @SerialName("asset_id") val assetId: String,
    @SerialName("taken_at") val takenAt: String?,
    val city: String?, val country: String?, val description: String?,
    val people: List<String>,
)

@Serializable data class WireSources(val albums: List<WireAlbum>, val people: List<WirePerson>)
@Serializable data class WireAlbum(val id: String, val name: String, val count: Int)
@Serializable data class WirePerson(val id: String, val name: String)

@Serializable data class WireMedia(
    val state: String, val volume: Float, val muted: Boolean,
    val url: String?, val title: String?, val artist: String?,
    @SerialName("album_art") val albumArt: String?,
    val duration: Double?, val position: Double?,
    @SerialName("position_updated_at") val positionUpdatedAt: String?,
    /** audio | video | image */
    val content: String? = null,
)

@Serializable data class WireAssist(
    val state: String,
    @SerialName("wake_word_enabled") val wakeWordEnabled: Boolean,
    @SerialName("wake_word") val wakeWord: String,
    @SerialName("available_wake_words") val availableWakeWords: List<String>,
)

@Serializable data class WireDevice(
    val battery: Int?, val charging: Boolean?,
    @SerialName("light_lux") val lightLux: Float?,
    @SerialName("wifi_rssi") val wifiRssi: Int?,
    val uptime: Long, val rooted: Boolean,
)

@Serializable data class WireWidgets(
    val clock: Boolean, val weather: Boolean,
    @SerialName("photo_info") val photoInfo: Boolean,
    val controls: Boolean,
    @SerialName("weather_entity") val weatherEntity: String?,
    val entities: List<String>,
    @SerialName("now_playing") val nowPlaying: Boolean,
    @SerialName("now_playing_entities") val nowPlayingEntities: List<String>,
)

@Serializable data class WireHa(val connected: Boolean, val url: String?)

@Serializable data class WireInfo(
    val id: String, val name: String, val model: String, val manufacturer: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("android_version") val androidVersion: String,
    val protocol: Int = 1,
)
