package app.photoframe.ui.widgets

import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.photoframe.AppGraph
import app.photoframe.media.MediaState
import app.photoframe.ui.glass.Backdrop
import app.photoframe.ui.glass.GlassSurface
import app.photoframe.ui.glass.backdropSource
import app.photoframe.ui.glass.rememberBackdrop
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Full-screen video / image sent from Home Assistant (media player, cameras, doorbell…). */
@Composable
fun VisualOverlay(g: AppGraph, m: MediaState) {
    val scope = rememberCoroutineScope()
    // The overlay is its own glass backdrop so the controls blur the video/image itself.
    val backdrop = rememberBackdrop()
    var controlsAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var controls by remember { mutableStateOf(true) }
    LaunchedEffect(controlsAt) { controls = true; delay(4000); controls = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { controlsAt = System.currentTimeMillis() } }
    ) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop), contentAlignment = Alignment.Center) {
            when (m.content) {
                "video" -> VideoSurface(g, m)
                "image" -> ImageView(g, m)
            }
        }

        AnimatedVisibility(controls, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), enter = fadeIn(), exit = fadeOut()) {
            VisualControls(backdrop, m,
                onPlayPause = { scope.launch { if (m.state == "playing") g.media.pause() else g.media.resume() }; controlsAt = System.currentTimeMillis() },
                onClose = { scope.launch { g.media.stop() } })
        }
    }
}

@Composable
private fun VideoSurface(g: AppGraph, m: MediaState) {
    // TextureView (not SurfaceView) so the glass controls can blur the video itself.
    DisposableEffect(Unit) { onDispose { g.media.setVideoSurface(null) } }
    // Keep the video's proportions; until we know them, fill the screen.
    val ratio = if (m.videoWidth > 0 && m.videoHeight > 0) m.videoWidth.toFloat() / m.videoHeight else null
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { g.media.setVideoSurface(it) } },
        modifier = if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
    )
}

@Composable
private fun ImageView(g: AppGraph, m: MediaState) {
    val url = m.url ?: return
    val abs = if (url.startsWith("/")) g.ha.absolute(url) else url
    val context = LocalContext.current
    // Load ourselves (one download shared by both layers) and keep showing the previous
    // picture until the next one is ready, so live camera snapshots never flash blank.
    var bitmap by remember(abs) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(abs, m.refreshSec) {
        while (true) {
            val req = ImageRequest.Builder(context).data(abs).apply {
                if (abs.startsWith(g.ha.baseUrl) && g.config.value.haToken.isNotBlank()) addHeader("Authorization", "Bearer ${g.config.value.haToken}")
                if (m.refreshSec != null) { memoryCachePolicy(CachePolicy.DISABLED); diskCachePolicy(CachePolicy.DISABLED) }
                allowHardware(true)
            }.build()
            val res = g.imageLoader.execute(req)
            if (res is SuccessResult) (res.drawable as? BitmapDrawable)?.bitmap?.let { bitmap = it.asImageBitmap() }
            val r = m.refreshSec ?: break
            delay((r * 1000).toLong().coerceAtLeast(500))
        }
    }
    val bmp = bitmap ?: return
    // Blurred fill behind, sharp fitted image on top (same look as the photos).
    Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(48.dp))
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    Image(bmp, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

@Composable
private fun VisualControls(backdrop: Backdrop, m: MediaState, onPlayPause: () -> Unit, onClose: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(m.state) { while (m.state == "playing") { now = System.currentTimeMillis(); delay(500) } }
    GlassSurface(backdrop, Modifier.widthIn(min = 360.dp, max = 760.dp), cornerRadius = 30.dp, tint = Color.Black.copy(alpha = 0.25f)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.content == "video") {
                    IconButton(onClick = onPlayPause) {
                        Icon(if (m.state == "playing") Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(m.title ?: if (m.content == "image") "Picture from Home Assistant" else "Video from Home Assistant",
                        style = GlassText.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    m.artist?.let { Text(it, style = GlassText.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f)), maxLines = 1) }
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            }
            val dur = m.duration; val pos = m.position
            if (m.content == "video" && dur != null && dur > 0 && pos != null) {
                val drift = if (m.state == "playing") (now - (m.positionUpdatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: now)) / 1000.0 else 0.0
                val p = (pos + drift.coerceAtLeast(0.0)).coerceAtMost(dur)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxWidth((p / dur).toFloat().coerceIn(0f, 1f)).height(4.dp).background(Color.White.copy(alpha = 0.9f)))
                }
            }
        }
    }
}

