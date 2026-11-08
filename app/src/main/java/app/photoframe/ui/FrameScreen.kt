package app.photoframe.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photoframe.AppGraph
import app.photoframe.assist.AssistPhase
import app.photoframe.core.Slide
import app.photoframe.core.SlideshowStatus
import app.photoframe.ha.HaClient
import app.photoframe.ui.glass.Backdrop
import app.photoframe.ui.glass.GlassSurface
import app.photoframe.ui.glass.backdropSource
import app.photoframe.ui.glass.rememberBackdrop
import app.photoframe.ui.settings.SettingsScreen
import app.photoframe.ui.widgets.AssistOverlay
import app.photoframe.ui.widgets.ClockWidget
import app.photoframe.ui.widgets.ControlTile
import app.photoframe.ui.widgets.NowPlayingCard
import app.photoframe.ui.widgets.VisualOverlay
import app.photoframe.ui.widgets.rememberNowPlaying
import app.photoframe.ui.widgets.GlassCircleButton
import app.photoframe.ui.widgets.GlassText
import app.photoframe.ui.widgets.PairingCard
import app.photoframe.ui.widgets.PhotoInfoWidget
import app.photoframe.ui.widgets.ToastCard
import app.photoframe.ui.widgets.WeatherWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

@Composable
fun FrameRoot(g: AppGraph) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var settingsOpen by remember { mutableStateOf(false) }
        // Kiosk: back never leaves the frame.
        BackHandler(enabled = !settingsOpen) {}

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            FrameScreen(g, onOpenSettings = { settingsOpen = true })
            if (settingsOpen) {
                SettingsScreen(g, onClose = { settingsOpen = false })
            }
        }
    }
}

@Composable
private fun FrameScreen(g: AppGraph, onOpenSettings: () -> Unit) {
    val backdrop = rememberBackdrop()
    val scope = rememberCoroutineScope()
    val cfg by g.config.config.collectAsState()
    val slide by g.slideshow.current.collectAsState()
    val status by g.slideshow.status.collectAsState()
    val error by g.slideshow.error.collectAsState()
    val screen by g.screen.state.collectAsState()
    val assist by g.assist.ui.collectAsState()
    // Immich videos play muted; a tap turns sound on (reset for every new video).
    var videoSound by remember { mutableStateOf(false) }
    var soundHint by remember { mutableStateOf(false) }
    val toast by g.ui.toast.collectAsState()
    val pairing by g.ui.pairingCode.collectAsState()
    val entities by g.ha.entities.collectAsState()
    val haStatus by g.ha.status.collectAsState()
    val mediaState by g.media.state.collectAsState()
    // Videos/images are shown full screen instead of in the now-playing card.
    val nowPlaying = rememberNowPlaying(mediaState.takeUnless { it.visual } ?: mediaState.copy(url = null), entities, cfg.nowPlayingEntities)
    val isVideoSlide = slide?.asset?.isVideo == true
    LaunchedEffect(slide?.seq) {
        videoSound = false
        if (slide?.asset?.isVideo == true) { soundHint = true; delay(4000); soundHint = false }
    }
    // Quiet any music on the frame while a video has sound (keyed on the user's choice only,
    // so Assist's own ducking isn't overridden).
    val videoSoundOn = isVideoSlide && videoSound
    LaunchedEffect(videoSoundOn) { g.media.duck(if (videoSoundOn) 0.15f else 1f) }

    // Controls auto-hide after 20 s without touch.
    var lastTouch by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(lastTouch) {
        if (lastTouch == 0L) return@LaunchedEffect
        controlsVisible = true; delay(20_000); controlsVisible = false
    }
    // Toast auto-dismiss
    LaunchedEffect(toast?.id) { toast?.let { delay(it.durationSec * 1000L); if (g.ui.toast.value?.id == it.id) g.ui.toast.value = null } }

    // Weather forecast refresh
    var forecast by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    LaunchedEffect(cfg.weatherEntity, haStatus) {
        val id = cfg.weatherEntity ?: return@LaunchedEffect
        if (haStatus != HaClient.Status.CONNECTED) return@LaunchedEffect
        while (true) {
            runCatching { forecast = g.ha.dailyForecast(id) }
            delay(30 * 60_000L)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        lastTouch = System.currentTimeMillis(); g.ui.touch.tryEmit(Unit); g.screen.wakeFromTouch(); g.assist.dismiss()
                        if (g.slideshow.current.value?.asset?.isVideo == true && !g.media.state.value.visual) {
                            videoSound = !videoSound; soundHint = false
                        }
                    },
                    onLongPress = { if (!cfg.showClock) onOpenSettings() },
                )
            }
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                    if (total < -120) scope.launch { g.slideshow.next() } else if (total > 120) scope.launch { g.slideshow.previous() }
                }) { _, dx -> total += dx }
            }
    ) {
        // ---------------- photos (the backdrop for all glass)
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            Crossfade(slide, animationSpec = tween(1600), label = "slides") { s ->
                if (s != null && s.asset.isVideo) {
                    VideoSlide(
                        g, s,
                        // Only the current video may make sound, and never while Assist listens/talks.
                        muted = !videoSound || s.seq != slide?.seq ||
                            assist.phase in setOf(AssistPhase.LISTENING, AssistPhase.PROCESSING, AssistPhase.RESPONDING),
                        paused = mediaState.visual || screen.nightMode || screen.softOff || !screen.on,
                        onEnded = { g.slideshow.onVideoEnded(s.seq) },
                    )
                } else if (s != null) PhotoLayer(s, cfg.kenBurns, cfg.intervalSec)
                else EmptyBackground()
            }
        }

        if (screen.nightMode) {
            NightClock(cfg.use24h)
        } else {
            // ---------------- widgets
            if (cfg.showClock) ClockWidget(backdrop, cfg.use24h, onLongPress = onOpenSettings, modifier = Modifier.align(Alignment.TopStart).padding(32.dp))
            if (cfg.showWeather) WeatherWidget(backdrop, cfg.weatherEntity?.let { entities[it] }, forecast, Modifier.align(Alignment.TopEnd).padding(32.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (cfg.showPhotoInfo && assist.visible.not()) PhotoInfoWidget(backdrop, slide?.asset)
                AnimatedVisibility(cfg.showNowPlaying && nowPlaying != null,
                    enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                    // Keep showing the last value while animating out.
                    val np = remember { mutableStateOf(nowPlaying) }
                    if (nowPlaying != null) np.value = nowPlaying
                    np.value?.let { cur ->
                        NowPlayingCard(
                            backdrop, cur, g,
                            onPlayPause = {
                                scope.launch {
                                    runCatching {
                                        if (cur.entityId == null) { if (cur.playing) g.media.pause() else g.media.resume() }
                                        else g.ha.callService("media_player", "media_play_pause", cur.entityId)
                                    }
                                }
                            },
                            onPrev = { cur.entityId?.let { id -> scope.launch { runCatching { g.ha.callService("media_player", "media_previous_track", id) } } } },
                            onNext = { cur.entityId?.let { id -> scope.launch { runCatching { g.ha.callService("media_player", "media_next_track", id) } } } },
                            onStop = { scope.launch { g.media.stop() } },
                        )
                    }
                }
            }

            Row(
                Modifier.align(Alignment.BottomEnd).padding(32.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                AnimatedVisibility(controlsVisible && cfg.showControls && cfg.controlEntities.isNotEmpty(), enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        cfg.controlEntities.take(5).mapNotNull { entities[it] }.forEach { e ->
                            ControlTile(backdrop, e) { lastTouch = System.currentTimeMillis(); scope.launch { runCatching { g.ha.toggle(e) } } }
                        }
                    }
                }
                AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                    GlassCircleButton(backdrop, Icons.Rounded.Settings, size = 56) { onOpenSettings() }
                }
                if (isVideoSlide && !screen.nightMode) {
                    GlassCircleButton(backdrop, if (videoSound) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, size = 56) {
                        videoSound = !videoSound; soundHint = false
                    }
                }
                if (haStatus != HaClient.Status.DISABLED) {
                    val listening = assist.phase == AssistPhase.LISTENING
                    GlassCircleButton(backdrop, Icons.Rounded.Mic, tint = if (listening) Color(0xFF5AA9FF).copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.12f)) {
                        lastTouch = System.currentTimeMillis(); g.assist.start()
                    }
                }
            }
        }

        // ---------------- "Tap for sound" hint at the start of each video
        AnimatedVisibility(soundHint && isVideoSlide && !screen.nightMode, Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
            enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            GlassSurface(backdrop, cornerRadius = 24.dp, tint = Color.Black.copy(alpha = 0.22f)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VolumeOff, null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text("Tap for sound", style = GlassText.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium))
                }
            }
        }

        // ---------------- video / image from Home Assistant (above photos & night clock)
        AnimatedVisibility(mediaState.visual, enter = fadeIn(tween(400)), exit = fadeOut(tween(400))) {
            val shown = remember { mutableStateOf(mediaState) }
            if (mediaState.visual) shown.value = mediaState
            VisualOverlay(g, shown.value)
        }

        // ---------------- status card when there are no photos yet
        if (slide == null && !mediaState.visual) {
            StatusCard(backdrop, status, error, onOpenSettings, Modifier.align(Alignment.Center))
        }

        // ---------------- overlays
        AnimatedVisibility(assist.visible, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
            AssistOverlay(backdrop, assist, onTap = { g.assist.cancel() })
        }
        AnimatedVisibility(toast != null, Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
            enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            toast?.let { ToastCard(backdrop, it, onDismiss = { g.ui.toast.value = null }) }
        }
        AnimatedVisibility(pairing != null, Modifier.align(Alignment.Center), enter = fadeIn() + scaleIn(), exit = fadeOut()) {
            pairing?.let { PairingCard(backdrop, it) }
        }

        // ---------------- soft "screen off" (no root)
        if (screen.softOff) Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { g.screen.wakeFromTouch() } })
    }
}

@Composable
private fun PhotoLayer(slide: Slide, kenBurns: Boolean, intervalSec: Int) {
    val bmp = remember(slide.seq) { slide.bitmap.asImageBitmap() }
    val rnd = remember(slide.seq) { Random(slide.seq) }
    val zoomIn = remember(slide.seq) { rnd.nextBoolean() }
    val panX = remember(slide.seq) { rnd.nextFloat() * 2 - 1 }
    val panY = remember(slide.seq) { rnd.nextFloat() * 2 - 1 }
    val progress = remember(slide.seq) { Animatable(0f) }
    LaunchedEffect(slide.seq, kenBurns) {
        if (kenBurns) progress.animateTo(1f, tween((intervalSec + 4) * 1000, easing = LinearEasing))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenAspect = maxWidth.value / maxHeight.value
        val photoAspect = bmp.width.toFloat() / bmp.height
        // Big mismatch (portrait photo on landscape frame): fit it over a blurred fill.
        val fit = photoAspect / screenAspect < 0.75f || photoAspect / screenAspect > 1.6f

        if (fit) {
            Image(bmp, null, Modifier.fillMaxSize().blur(48.dp).drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = 0.35f)) }, contentScale = ContentScale.Crop)
        }
        Image(
            bmp, null,
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                if (kenBurns) {
                    val p = progress.value
                    val s = if (zoomIn) 1.0f + 0.10f * p else 1.10f - 0.10f * p
                    scaleX = s; scaleY = s
                    val maxShift = (s - 1f) / 2f
                    translationX = panX * maxShift * size.width * (if (zoomIn) p else 1 - p)
                    translationY = panY * maxShift * size.height * (if (zoomIn) p else 1 - p)
                }
            },
        )
    }
}

@Composable
private fun EmptyBackground() {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B2A41), Color(0xFF3B2F4A), Color(0xFF6B4E5E)))))
}

@Composable
private fun StatusCard(backdrop: Backdrop, status: SlideshowStatus, error: String?, onOpenSettings: () -> Unit, modifier: Modifier) {
    GlassSurface(backdrop, modifier, cornerRadius = 36.dp, tint = Color.Black.copy(alpha = 0.2f)) {
        Column(Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val (title, body) = when (status) {
                SlideshowStatus.NOT_CONFIGURED -> "Welcome to your photo frame" to "Connect it to Immich to start showing photos."
                SlideshowStatus.LOADING -> "Loading photos…" to "Talking to Immich"
                SlideshowStatus.EMPTY -> "No photos found" to "Pick an album or person in settings."
                SlideshowStatus.ERROR -> "Couldn't reach Immich" to (error ?: "Check the server address and API key.")
                SlideshowStatus.READY -> "" to ""
            }
            Text(title, style = GlassText.copy(fontSize = 30.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(8.dp))
            Text(body, style = GlassText.copy(fontSize = 18.sp, color = Color.White.copy(alpha = 0.85f)))
            if (status != SlideshowStatus.LOADING) {
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenSettings) { Text("Open settings") }
            }
        }
    }
}

@Composable
private fun NightClock(use24h: Boolean) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(5000) } }
    // Slowly drift to avoid burn-in.
    val drift = remember(now.minute) { Random.nextInt(-40, 40) to Random.nextInt(-30, 30) }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text(
            now.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm")),
            style = GlassText.copy(fontSize = 140.sp, fontWeight = FontWeight.Thin, color = Color(0xFFB9A88F)),
            modifier = Modifier.padding(start = (60 + drift.first).dp, top = (40 + drift.second).dp).width(600.dp),
        )
    }
}
