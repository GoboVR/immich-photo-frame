package app.photoframe.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceState(
    val battery: Int? = null,
    val charging: Boolean? = null,
    val lux: Float? = null,
    val wifiRssi: Int? = null,
    val uptime: Long = 0,
    val screenInteractive: Boolean = true,
)

class DeviceMonitor(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state
    private val startedAt = SystemClock.elapsedRealtime()

    fun start() {
        val battery = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                _state.update { it.copy(battery = if (level >= 0) level * 100 / scale else null, charging = charging) }
            }
        }
        context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val screen = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                _state.update { it.copy(screenInteractive = i.action == Intent.ACTION_SCREEN_ON) }
            }
        }
        context.registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })

        val sm = context.getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { light ->
            sm.registerListener(object : SensorEventListener {
                private var lastPush = 0L
                override fun onSensorChanged(e: SensorEvent) {
                    val now = SystemClock.elapsedRealtime()
                    val v = e.values[0]
                    val old = _state.value.lux
                    // Rate limit: max every 2 s unless the change is large.
                    if (old == null || now - lastPush > 2000 || kotlin.math.abs(v - old) > old * 0.5f + 5) {
                        lastPush = now
                        _state.update { it.copy(lux = (v * 10).toInt() / 10f) }
                    }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }, light, SensorManager.SENSOR_DELAY_NORMAL)
        }

        scope.launch {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            while (true) {
                @Suppress("DEPRECATION")
                val rssi = runCatching { wifi.connectionInfo.rssi }.getOrNull()?.takeIf { it > -127 && it < 0 }
                _state.update { it.copy(wifiRssi = rssi, uptime = (SystemClock.elapsedRealtime() - startedAt) / 1000) }
                delay(30_000)
            }
        }
    }
}
