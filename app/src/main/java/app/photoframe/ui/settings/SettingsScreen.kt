package app.photoframe.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import app.photoframe.core.randomKey
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.photoframe.AppGraph
import app.photoframe.assist.WakeWordEngine
import app.photoframe.core.FrameConfig
import app.photoframe.ha.HaClient
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(g: AppGraph, onClose: () -> Unit) {
    val cfg by g.config.config.collectAsState()
    val albums by g.slideshow.albums.collectAsState()
    val people by g.slideshow.people.collectAsState()
    val haStatus by g.ha.status.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    fun close() { focus.clearFocus(); onClose() }
    BackHandler { close() }
    fun upd(block: (FrameConfig) -> FrameConfig) = g.config.update(block)

    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Color(0xFF101318), contentColor = Color(0xFFE6E8EC)) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Frame settings") },
                navigationIcon = { IconButton(onClick = ::close) { Icon(Icons.Rounded.ArrowBack, "Back") } },
            )
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ------------------------------------------------------ frame
                Section("Frame") {
                    ConfigField("Name", cfg.frameName) { v -> upd { it.copy(frameName = v.ifBlank { "Photo Frame" }) } }
                    Text("Address: ${localIp() ?: "unknown"}:${cfg.serverPort}   ·   Root: ${if (g.rooted) "yes" else "no"}",
                        style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                }

                // ------------------------------------------------------ immich
                Section("Immich") {
                    var testResult by remember { mutableStateOf<String?>(null) }
                    ConfigField("Server URL (e.g. http://192.168.1.10:2283)", cfg.immichUrl) { v -> upd { it.copy(immichUrl = v.trim()) } }
                    ConfigField("API key", cfg.immichApiKey, secret = true) { v -> upd { it.copy(immichApiKey = v.trim()) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            focus.clearFocus()
                            scope.launch {
                                testResult = runCatching { "Connected · Immich ${g.immich.ping()}" }.getOrElse { "Failed: ${it.message}" }
                                g.slideshow.reloadSources()
                            }
                        }) { Text("Test connection") }
                        Spacer(Modifier.width(16.dp))
                        testResult?.let { Text(it) }
                    }
                    SwitchRow("Full resolution photos", cfg.fullResolution) { v -> upd { it.copy(fullResolution = v) } }
                }

                // ------------------------------------------------------ photos
                Section("Photos") {
                    Text("Show photos from", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("mixed" to "Everything", "album" to "Albums", "people" to "People", "memories" to "On this day", "random" to "Random").forEach { (k, label) ->
                            FilterChip(selected = cfg.source == k, onClick = { upd { it.copy(source = k) } }, label = { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Albums", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { g.slideshow.reloadSources() } }) { Text("Refresh") }
                    }
                    if (albums.isEmpty()) Text("No albums loaded yet.", color = Color.White.copy(alpha = 0.6f))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        albums.forEach { a ->
                            val on = a.id in cfg.albumIds
                            FilterChip(selected = on, onClick = {
                                upd { it.copy(albumIds = if (on) it.albumIds - a.id else it.albumIds + a.id) }
                            }, label = { Text("${a.name} (${a.count})") })
                        }
                    }
                    Text("People", style = MaterialTheme.typography.labelLarge)
                    if (people.isEmpty()) Text("No named people found in Immich.", color = Color.White.copy(alpha = 0.6f))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        people.forEach { p ->
                            val on = p.id in cfg.personIds
                            FilterChip(selected = on, onClick = {
                                upd { it.copy(personIds = if (on) it.personIds - p.id else it.personIds + p.id) }
                            }, label = { Text(p.name) })
                        }
                    }
                    SliderRow("Change photo every", cfg.intervalSec.toFloat(), 5f..300f, { "${it.toInt()} s" }) { v -> upd { it.copy(intervalSec = v.toInt()) } }
                    SwitchRow("Slow zoom (Ken Burns)", cfg.kenBurns) { v -> upd { it.copy(kenBurns = v) } }
                    SwitchRow("Include videos (muted, tap for sound)", cfg.includeVideos) { v -> upd { it.copy(includeVideos = v) } }
                    if (cfg.includeVideos) SliderRow("Skip videos longer than", cfg.maxVideoSec.toFloat(), 10f..300f, { "${it.toInt()} s" }) { v ->
                        upd { it.copy(maxVideoSec = v.toInt()) }
                    }
                }

                // ------------------------------------------------------ home assistant
                Section("Home Assistant") {
                    Text(
                        when (haStatus) {
                            HaClient.Status.CONNECTED -> "● Connected"
                            HaClient.Status.CONNECTING -> "○ Connecting…"
                            HaClient.Status.AUTH_FAILED -> "✕ Token rejected"
                            HaClient.Status.DISCONNECTED -> "✕ Disconnected (retrying)"
                            HaClient.Status.DISABLED -> "Not set up. Add the Photo Frame integration in Home Assistant, or enter details below."
                        },
                        color = if (haStatus == HaClient.Status.CONNECTED) Color(0xFF8BE28B) else Color.White.copy(alpha = 0.75f),
                    )
                    val haError by g.ha.lastError.collectAsState()
                    if (haStatus == HaClient.Status.CONNECTED) {
                        Text("Using ${g.ha.baseUrl}", color = Color.White.copy(alpha = 0.7f))
                    } else haError?.let { Text("Last error: $it", color = Color(0xFFFFB4A8)) }
                    if (cfg.haUrls.size > 1) Text("Also trying: ${cfg.haUrls.filter { it != cfg.haUrl }.joinToString(", ")}",
                        color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                    ConfigField("URL the frame uses (e.g. http://192.168.1.20:8123)", cfg.haUrl) { v ->
                        upd { it.copy(haUrl = v.trim().trimEnd('/'), haActiveUrl = null) }
                    }
                    ConfigField("Long-lived access token", cfg.haToken, secret = true) { v -> upd { it.copy(haToken = v.trim()) } }
                    ConfigField("Weather entity (e.g. weather.home)", cfg.weatherEntity.orEmpty()) { v -> upd { it.copy(weatherEntity = v.trim().ifBlank { null }) } }
                    ConfigField("Quick controls (comma separated entity ids)", cfg.controlEntities.joinToString(", ")) { v ->
                        upd { it.copy(controlEntities = v.split(',').map(String::trim).filter(String::isNotEmpty)) }
                    }
                    var pipelines by remember { mutableStateOf<List<HaClient.Pipeline>>(emptyList()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Assist pipeline: ${pipelines.firstOrNull { it.id == cfg.assistPipelineId }?.name ?: if (cfg.assistPipelineId == null) "Preferred" else cfg.assistPipelineId}",
                            modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { scope.launch { pipelines = runCatching { g.ha.pipelines() }.getOrDefault(emptyList()) } }) { Text("Load pipelines") }
                    }
                    if (pipelines.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = cfg.assistPipelineId == null, onClick = { upd { it.copy(assistPipelineId = null) } }, label = { Text("Preferred") })
                        pipelines.forEach { p ->
                            FilterChip(selected = cfg.assistPipelineId == p.id, onClick = { upd { it.copy(assistPipelineId = p.id) } }, label = { Text(p.name) })
                        }
                    }
                }

                // ------------------------------------------------------ assist
                Section("Voice") {
                    SwitchRow("Wake word", cfg.wakeWordEnabled) { v -> upd { it.copy(wakeWordEnabled = v) } }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WakeWordEngine.MODELS.keys.forEach { w ->
                            FilterChip(selected = cfg.wakeWord == w, onClick = { upd { it.copy(wakeWord = w) } },
                                label = { Text(w.split('_').joinToString(" ") { s -> s.replaceFirstChar(Char::uppercase) }) })
                        }
                    }
                    SliderRow("Sensitivity", 1f - cfg.wakeWordThreshold, 0.2f..0.8f, { "${(it * 100).toInt()}%" }) { v -> upd { it.copy(wakeWordThreshold = 1f - v) } }
                    Button(onClick = { close(); g.assist.start() }) { Text("Try Assist now") }
                }

                // ------------------------------------------------------ widgets
                Section("Widgets") {
                    SwitchRow("Clock", cfg.showClock) { v -> upd { it.copy(showClock = v) } }
                    SwitchRow("24-hour clock", cfg.use24h) { v -> upd { it.copy(use24h = v) } }
                    SwitchRow("Weather", cfg.showWeather) { v -> upd { it.copy(showWeather = v) } }
                    SwitchRow("Photo location & date", cfg.showPhotoInfo) { v -> upd { it.copy(showPhotoInfo = v) } }
                    SwitchRow("Quick controls (appear on tap)", cfg.showControls) { v -> upd { it.copy(showControls = v) } }
                    SwitchRow("Now playing", cfg.showNowPlaying) { v -> upd { it.copy(showNowPlaying = v) } }
                    ConfigField("Now playing: other speakers (media_player entity ids, comma separated)", cfg.nowPlayingEntities.joinToString(", ")) { v ->
                        upd { it.copy(nowPlayingEntities = v.split(',').map(String::trim).filter(String::isNotEmpty)) }
                    }
                }

                // ------------------------------------------------------ screen
                Section("Screen") {
                    SwitchRow("Auto brightness (light sensor)", cfg.autoBrightness) { v -> upd { it.copy(autoBrightness = v) } }
                    if (!cfg.autoBrightness) SliderRow("Brightness", cfg.brightness.toFloat(), 1f..255f, { "${(it / 2.55f).toInt()}%" }) { v -> upd { it.copy(brightness = v.toInt()) } }
                    SwitchRow("Night mode now", cfg.nightMode) { v -> upd { it.copy(nightMode = v) } }
                    SwitchRow("Night mode on a schedule", cfg.autoNightMode) { v -> upd { it.copy(autoNightMode = v) } }
                    if (cfg.autoNightMode) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { ConfigField("From (HH:mm)", cfg.nightStart) { v -> upd { it.copy(nightStart = v.trim()) } } }
                        Box(Modifier.weight(1f)) { ConfigField("Until (HH:mm)", cfg.nightEnd) { v -> upd { it.copy(nightEnd = v.trim()) } } }
                    }
                }

                // ------------------------------------------------------ live camera
                Section("Live camera") {
                    val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        if (granted) upd { it.copy(cameraEnabled = true) }
                    }
                    if (!g.camera.available) {
                        Text("This tablet has no camera.", color = Color.White.copy(alpha = 0.7f))
                    } else {
                        SwitchRow("Live camera (front camera)", cfg.cameraEnabled) { on ->
                            if (on && !g.camera.hasPermission) camPermission.launch(android.Manifest.permission.CAMERA)
                            else upd { it.copy(cameraEnabled = on) }
                        }
                        Text(
                            "Only runs while someone is watching, and the frame always shows \"Camera is being viewed\" while it's live. " +
                                "Home Assistant gets a live camera entity automatically. Use the RTSP link for smooth video in VLC or Frigate, and the viewer page in a browser.",
                            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium,
                        )
                        if (cfg.cameraEnabled) {
                            val base = "http://${localIp() ?: "<frame-ip>"}:${cfg.serverPort}"
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Live video (VLC, Frigate):  rtsp://${localIp() ?: "<frame-ip>"}:${cfg.rtspPort}/camera?key=${cfg.cameraKey}")
                                    Text("Viewer page (browser):  $base/camera?key=${cfg.cameraKey}")
                                    Text("MJPEG stream:  $base/api/v1/camera.mjpeg?key=${cfg.cameraKey}")
                                    Text("Snapshot:  $base/api/v1/camera.jpg?key=${cfg.cameraKey}")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("The key only allows watching the camera, not controlling the frame.",
                                    modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { upd { it.copy(cameraKey = randomKey()) } }) { Text("New key") }
                            }
                            SwitchRow("HD (1280×720, more bandwidth)", cfg.cameraHd) { v -> upd { it.copy(cameraHd = v) } }
                            SliderRow("Browser/MJPEG frames per second", cfg.cameraFps.toFloat(), 2f..20f, { "${it.toInt()}" }) { v -> upd { it.copy(cameraFps = v.toInt()) } }
                        }
                    }
                }

                // ------------------------------------------------------ integration
                Section("Paired Home Assistant instances") {
                    if (cfg.serverTokens.isEmpty()) Text("None yet. In Home Assistant: Settings → Devices & services → Add integration → Photo Frame.", color = Color.White.copy(alpha = 0.7f))
                    cfg.serverTokens.forEach { (token, name) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$name  ·  …${token.takeLast(6)}", modifier = Modifier.weight(1f))
                            IconButton(onClick = { upd { it.copy(serverTokens = it.serverTokens - token) } }) { Icon(Icons.Rounded.Delete, "Revoke") }
                        }
                    }
                }

                Section("Device") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Set as home app") }
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Android settings") }
                    }
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().widthIn(max = 900.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1F27)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/** Text field that commits on Done or when it loses focus (not every keystroke). */
@Composable
private fun ConfigField(label: String, value: String, secret: Boolean = false, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    DisposableEffect(Unit) { onDispose { if (text != value) onCommit(text) } }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret && !focused) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { st ->
            if (focused && !st.isFocused && text != value) onCommit(text)
            focused = st.isFocused
        },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, onCommit: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row { Text(label, modifier = Modifier.weight(1f)); Text(format(v)) }
        Slider(value = v, onValueChange = { v = it }, valueRange = range, onValueChangeFinished = { onCommit(v) })
    }
}

private fun localIp(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address }?.hostAddress
}.getOrNull()
