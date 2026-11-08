package app.photoframe.device

import android.content.Context
import android.os.PowerManager
import app.photoframe.core.ConfigStore
import app.photoframe.core.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.log10

data class ScreenState(
    val on: Boolean = true,
    val brightness: Int = 200,
    val autoBrightness: Boolean = false,
    val nightMode: Boolean = false,
    /** 0..1 value to apply to the window */
    val effectiveBrightness: Float = 0.8f,
    /** true when screen is "off" without root (we draw black instead) */
    val softOff: Boolean = false,
)

class ScreenController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: ConfigStore,
    private val device: DeviceMonitor,
) {
    private val wantOn = MutableStateFlow(true)
    private val scheduledNight = MutableStateFlow(false)

    val state: StateFlow<ScreenState> = combine(config.config, device.state, wantOn, scheduledNight) { cfg, dev, on, schedNight ->
        val night = cfg.nightMode || (cfg.autoNightMode && schedNight)
        val base = if (cfg.autoBrightness && dev.lux != null) {
            // ~1 lux → dim, ~1000+ lux → full
            ((log10(dev.lux + 1f) / 3f).coerceIn(0.03f, 1f))
        } else cfg.brightness / 255f
        val eff = if (night) minOf(base, 0.05f) else base
        val rooted = RootShell.isRooted
        ScreenState(
            on = on && dev.screenInteractive,
            brightness = cfg.brightness,
            autoBrightness = cfg.autoBrightness,
            nightMode = night,
            effectiveBrightness = eff.coerceIn(0.01f, 1f),
            softOff = !on && !rooted,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ScreenState())

    fun start() {
        // Physical power button / other wake sources → we're on again.
        scope.launch {
            var last = true
            device.state.collect { if (it.screenInteractive && !last) wantOn.value = true; last = it.screenInteractive }
        }
        scope.launch {
            while (true) {
                val c = config.value
                scheduledNight.value = inWindow(LocalTime.now(), c.nightStart, c.nightEnd)
                delay(30_000)
            }
        }
    }

    private fun inWindow(now: LocalTime, start: String, end: String): Boolean = runCatching {
        val s = LocalTime.parse(start); val e = LocalTime.parse(end)
        if (s <= e) now >= s && now < e else now >= s || now < e
    }.getOrDefault(false)

    suspend fun turnOff() {
        wantOn.value = false
        if (RootShell.isRooted) RootShell.exec("input keyevent 223")
    }

    suspend fun turnOn() {
        wantOn.value = true
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isInteractive && RootShell.isRooted) {
            RootShell.exec("input keyevent 224")
            // Dismiss a swipe keyguard if one is configured.
            delay(300); RootShell.exec("wm dismiss-keyguard")
        }
    }

    /** Called when the user touches a soft-off screen. */
    fun wakeFromTouch() { wantOn.value = true }

    fun setBrightness(b: Int) = config.update { it.copy(brightness = b.coerceIn(1, 255), autoBrightness = false) }
    fun setAutoBrightness(v: Boolean) = config.update { it.copy(autoBrightness = v) }
    fun setNightMode(v: Boolean) = config.update { it.copy(nightMode = v) }
}
