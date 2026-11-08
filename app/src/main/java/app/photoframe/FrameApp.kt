package app.photoframe

import android.app.Application
import android.os.Build
import android.util.Log
import app.photoframe.assist.AssistController
import app.photoframe.assist.AudioCapture
import app.photoframe.assist.WakeWordEngine
import app.photoframe.core.ConfigStore
import app.photoframe.core.RootShell
import app.photoframe.core.SlideshowController
import app.photoframe.core.UiBus
import app.photoframe.device.DeviceMonitor
import app.photoframe.device.ScreenController
import app.photoframe.ha.HaClient
import app.photoframe.immich.ImmichClient
import app.photoframe.media.MediaEngine
import app.photoframe.server.CommandHandler
import app.photoframe.server.Discovery
import app.photoframe.server.FrameServer
import app.photoframe.server.WireAlbum
import app.photoframe.server.WireAssist
import app.photoframe.server.WireCurrent
import app.photoframe.server.WireDevice
import app.photoframe.server.WireHa
import app.photoframe.server.WireInfo
import app.photoframe.server.WireMedia
import app.photoframe.server.WirePerson
import app.photoframe.server.WireScreen
import app.photoframe.server.WireSlideshow
import app.photoframe.server.WireSources
import app.photoframe.server.WireState
import app.photoframe.server.WireWidgets
import coil.ImageLoader
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class FrameApp : Application() {
    lateinit var graph: AppGraph; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        graph = AppGraph(this)
    }

    companion object {
        lateinit var instance: FrameApp; private set
        val graph: AppGraph get() = instance.graph
    }
}

class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("Frame", "uncaught", e) })
    val config = ConfigStore(app)
    val ui = UiBus()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    val imageLoader = ImageLoader.Builder(app).okHttpClient(http).crossfade(false).build()

    val immich = ImmichClient(http, { config.value.immichUrl }, { config.value.immichApiKey })
    val ha = HaClient(
        http, scope,
        urlsProvider = { config.value.let { c -> listOfNotNull(c.haActiveUrl, c.haUrl) + c.haUrls } },
        tokenProvider = { config.value.haToken },
        onWorkingUrl = { url -> if (config.value.haActiveUrl != url) config.update { it.copy(haActiveUrl = url) } },
    )
    val slideshow = SlideshowController(app, scope, config, immich, imageLoader)
        .also { s -> s.hold = { media.state.value.visual } } // don't advance under a video/image
    val media = MediaEngine(app, scope)
    val mic = AudioCapture()
    val assist = AssistController(app, scope, config, ha, media, mic)
    val device = DeviceMonitor(app, scope)
    val screen = ScreenController(app, scope, config, device)
    val commands = CommandHandler(this)
    val server = FrameServer(this)
    val discovery = Discovery(app)

    val rooted: Boolean get() = RootShell.isRooted
    private var started = false

    /** Start background machinery. Idempotent; called from FrameService. */
    fun start() {
        if (started) return
        started = true
        device.start()
        screen.start()
        media.start()
        slideshow.start()
        assist.startWakeWordWatcher()
        // Reconnect to HA when its URL/token change.
        scope.launch {
            config.config.map { Triple(it.haUrl, it.haUrls, it.haToken) }.distinctUntilChanged().collect { ha.restart() }
        }
        // Keep the widget entity subscription in sync.
        scope.launch {
            config.config.map { listOfNotNull(it.weatherEntity) + it.controlEntities + it.nowPlayingEntities }
                .distinctUntilChanged().collect { ha.watchEntities(it) }
        }
        // Re-advertise when name/port change.
        scope.launch {
            config.config.map { Triple(it.frameName, it.serverPort, it.frameId) }.distinctUntilChanged().collect { (name, port, id) ->
                server.restart(port)
                discovery.register(name, port, id)
            }
        }
        // Root conveniences: keep us alive and grant permissions without prompts.
        scope.launch {
            if (RootShell.isRooted) {
                val pkg = app.packageName
                RootShell.exec("dumpsys deviceidle whitelist +$pkg")
                RootShell.exec("pm grant $pkg android.permission.RECORD_AUDIO")
                RootShell.exec("pm grant $pkg android.permission.POST_NOTIFICATIONS")
                RootShell.exec("appops set $pkg SYSTEM_ALERT_WINDOW allow")
                assist.refreshWakeWord()
            }
        }
    }

    // ------------------------------------------------------------------ state

    fun info() = WireInfo(
        id = config.value.frameId,
        name = config.value.frameName,
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        appVersion = BuildConfig.VERSION_NAME,
        androidVersion = Build.VERSION.RELEASE,
    )

    fun snapshot(): WireState {
        val c = config.value
        val sc = screen.state.value
        val cur = slideshow.current.value?.asset
        val m = media.state.value
        val d = device.state.value
        return WireState(
            id = c.frameId, name = c.frameName,
            screen = WireScreen(sc.on, c.brightness, c.autoBrightness, sc.nightMode),
            slideshow = WireSlideshow(
                playing = slideshow.playing.value, interval = c.intervalSec, source = c.source,
                albumIds = c.albumIds, personIds = c.personIds,
                current = cur?.let { WireCurrent(it.id, it.takenAt, it.city, it.country, it.description, it.people) },
            ),
            sources = WireSources(
                slideshow.albums.value.map { WireAlbum(it.id, it.name, it.count) },
                slideshow.people.value.map { WirePerson(it.id, it.name) },
            ),
            media = WireMedia(m.state, m.volume, m.muted, m.url, m.title, m.artist, m.albumArt, m.duration, m.position, m.positionUpdatedAt,
                content = if (m.url != null) m.content else null),
            assist = WireAssist(assist.ui.value.phase.wire, c.wakeWordEnabled, c.wakeWord, WakeWordEngine.MODELS.keys.toList()),
            device = WireDevice(d.battery, d.charging, d.lux, d.wifiRssi, d.uptime, rooted),
            widgets = WireWidgets(c.showClock, c.showWeather, c.showPhotoInfo, c.showControls, c.weatherEntity, c.controlEntities,
                c.showNowPlaying, c.nowPlayingEntities),
            ha = WireHa(ha.status.value == HaClient.Status.CONNECTED, ha.baseUrl.ifBlank { null }),
        )
    }

    /** Emits a fresh snapshot whenever anything relevant changes (max 4/s). */
    @OptIn(FlowPreview::class)
    val wireState: StateFlow<WireState> by lazy {
        val sources: List<Flow<Any?>> = listOf(
            config.config, screen.state, slideshow.current, slideshow.playing, slideshow.albums, slideshow.people,
            media.state.map { it.copy(position = null, positionUpdatedAt = it.positionUpdatedAt) },
            assist.ui.map { it.phase }, device.state.map { it.copy(uptime = it.uptime / 300) }, ha.status,
        )
        merge(*sources.toTypedArray()).sample(250).map { snapshot() }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, snapshot())
    }
}
