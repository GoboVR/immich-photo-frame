package app.photoframe.ui.widgets

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photoframe.AppGraph
import app.photoframe.ha.HaEntity
import app.photoframe.media.MediaState
import app.photoframe.ui.glass.Backdrop
import app.photoframe.ui.glass.GlassSurface
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant

/** What the now-playing card shows, from the frame's own player or an HA media_player. */
data class NowPlaying(
    val key: String,              // stable id for the source (frame / entity id)
    val title: String,
    val artist: String?,
    val source: String?,          // e.g. "Kitchen speaker · YouTube Music"
    val artUrl: String?,
    val playing: Boolean,
    val duration: Double?,
    val position: Double?,
    val positionAt: Instant?,
    val entityId: String?,        // null = the frame itself
    val canPrev: Boolean,
    val canNext: Boolean,
)

private const val SUPPORT_PREVIOUS = 16
private const val SUPPORT_NEXT = 32
private const val PAUSED_GRACE_MS = 5 * 60_000L

private fun HaEntity.num(key: String) = (attributes[key] as? JsonPrimitive)?.doubleOrNull

fun frameNowPlaying(m: MediaState): NowPlaying? {
    if (m.url == null || m.state == "idle") return null
    val fallbackTitle = runCatching { Uri.parse(m.url).lastPathSegment }.getOrNull()
        ?.substringBeforeLast('.')?.replace('_', ' ')?.takeIf { it.isNotBlank() }
    return NowPlaying(
        key = "frame",
        title = m.title ?: fallbackTitle ?: "Playing",
        artist = m.artist,
        source = "On this frame",
        artUrl = m.albumArt,
        playing = m.state == "playing" || m.state == "buffering",
        duration = m.duration, position = m.position,
        positionAt = m.positionUpdatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        entityId = null, canPrev = false, canNext = false,
    )
}

fun entityNowPlaying(e: HaEntity): NowPlaying? {
    if (e.state != "playing" && e.state != "paused" && e.state != "buffering") return null
    val title = e.attr("media_title") ?: return null
    val features = (e.attributes["supported_features"] as? JsonPrimitive)?.intOrNull ?: 0
    val artist = e.attr("media_artist") ?: e.attr("media_album_artist") ?: e.attr("media_series_title")
    val app = e.attr("app_name")
    return NowPlaying(
        key = e.entityId,
        title = title,
        artist = artist,
        source = listOfNotNull(e.name, app?.takeIf { !e.name.contains(it, ignoreCase = true) }).joinToString(" · "),
        artUrl = e.attr("entity_picture"),
        playing = e.state != "paused",
        duration = e.num("media_duration"),
        position = e.num("media_position"),
        positionAt = e.attr("media_position_updated_at")?.let { runCatching { Instant.parse(it) }.getOrNull() },
        entityId = e.entityId,
        canPrev = features and SUPPORT_PREVIOUS != 0,
        canNext = features and SUPPORT_NEXT != 0,
    )
}

/**
 * Picks what to show: anything playing (frame first), else whatever was paused in the
 * last few minutes. Paused speakers disappear after [PAUSED_GRACE_MS] so the frame
 * doesn't show a stale song all night.
 */
@Composable
fun rememberNowPlaying(media: MediaState, entities: Map<String, HaEntity>, watched: List<String>): NowPlaying? {
    val lastPlaying = remember { mutableMapOf<String, Long>() }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick

    val candidates = listOfNotNull(frameNowPlaying(media)) + watched.mapNotNull { entities[it]?.let(::entityNowPlaying) }
    val now = System.currentTimeMillis()
    candidates.filter { it.playing }.forEach { lastPlaying[it.key] = now }
    return candidates.firstOrNull { it.playing }
        ?: candidates.firstOrNull { now - (lastPlaying[it.key] ?: 0L) < PAUSED_GRACE_MS }
}

@Composable
fun NowPlayingCard(
    backdrop: Backdrop,
    np: NowPlaying,
    g: AppGraph,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Live progress
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(np.playing) { while (np.playing) { now = System.currentTimeMillis(); delay(1000) } }
    val pos = np.position?.let { p ->
        val drift = if (np.playing && np.positionAt != null) (now - np.positionAt.toEpochMilli()) / 1000.0 else 0.0
        (p + drift.coerceAtLeast(0.0)).coerceAtMost(np.duration ?: Double.MAX_VALUE)
    }

    GlassSurface(backdrop, modifier.width(460.dp), cornerRadius = 30.dp, tint = Color.Black.copy(alpha = 0.22f)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(np.artUrl, transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) }, label = "art") { url ->
                    Artwork(g, url)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(np.title, style = GlassText.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    np.artist?.let { Text(it, style = GlassText.copy(fontSize = 16.sp, color = Color.White.copy(alpha = 0.88f)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    np.source?.let { Text(it, style = GlassText.copy(fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (np.canPrev) ControlButton(Icons.Rounded.SkipPrevious, 30, onPrev)
                ControlButton(if (np.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 38, onPlayPause)
                if (np.canNext) ControlButton(Icons.Rounded.SkipNext, 30, onNext)
                if (np.entityId == null) ControlButton(Icons.Rounded.Stop, 28, onStop)
            }
            val dur = np.duration
            if (pos != null && dur != null && dur > 0) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxWidth((pos / dur).toFloat().coerceIn(0f, 1f)).height(4.dp).background(Color.White.copy(alpha = 0.9f)))
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(fmt(pos), style = GlassText.copy(fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f)), modifier = Modifier.weight(1f))
                    Text("-" + fmt((dur - pos).coerceAtLeast(0.0)), style = GlassText.copy(fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f)))
                }
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, size: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size((size + 16).dp)) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size.dp))
    }
}

@Composable
private fun Artwork(g: AppGraph, url: String?) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.size(76.dp).clip(shape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(34.dp))
        if (url != null) {
            val abs = if (url.startsWith("/")) g.ha.absolute(url) else url
            val req = ImageRequest.Builder(LocalContext.current).data(abs).apply {
                // HA proxy URLs carry a token already; add auth for other HA-hosted images.
                if (abs.startsWith(g.ha.baseUrl) && g.config.value.haToken.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${g.config.value.haToken}")
                }
            }.crossfade(true).build()
            AsyncImage(req, null, imageLoader = g.imageLoader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun fmt(sec: Double): String {
    val s = sec.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
