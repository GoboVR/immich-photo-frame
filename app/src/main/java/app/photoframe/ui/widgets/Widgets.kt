package app.photoframe.ui.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.photoframe.assist.AssistPhase
import app.photoframe.assist.AssistUi
import app.photoframe.core.ToastMessage
import app.photoframe.ha.HaEntity
import app.photoframe.immich.Asset
import app.photoframe.ui.glass.Backdrop
import app.photoframe.ui.glass.GlassSurface
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val soft = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 8f)
val GlassText = TextStyle(color = Color.White, shadow = soft)

// ------------------------------------------------------------------ clock

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ClockWidget(backdrop: Backdrop, use24h: Boolean, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(1000L - System.currentTimeMillis() % 1000) } }
    val time = now.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm"))
    val ampm = if (use24h) "" else now.format(DateTimeFormatter.ofPattern("a", Locale.getDefault()))
    val date = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
    GlassSurface(
        backdrop,
        modifier.combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = onLongPress),
        cornerRadius = 32.dp,
    ) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(time, style = GlassText.copy(fontSize = 72.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp))
                if (ampm.isNotEmpty()) Text(" $ampm", style = GlassText.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium), modifier = Modifier.padding(bottom = 14.dp))
            }
            Text(date, style = GlassText.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.9f)))
        }
    }
}

// ------------------------------------------------------------------ weather

fun weatherIcon(condition: String?): ImageVector = when (condition) {
    "sunny" -> Icons.Rounded.WbSunny
    "clear-night" -> Icons.Rounded.NightsStay
    "partlycloudy" -> Icons.Rounded.FilterDrama
    "cloudy" -> Icons.Rounded.Cloud
    "rainy", "pouring" -> Icons.Rounded.Umbrella
    "lightning", "lightning-rainy" -> Icons.Rounded.Thunderstorm
    "snowy", "snowy-rainy", "hail" -> Icons.Rounded.AcUnit
    "fog" -> Icons.Rounded.BlurOn
    "windy", "windy-variant" -> Icons.Rounded.Air
    else -> Icons.Rounded.WbCloudy
}

fun conditionLabel(c: String?): String = when (c) {
    null -> ""
    "partlycloudy" -> "Partly cloudy"
    "clear-night" -> "Clear"
    "lightning-rainy" -> "Thunderstorms"
    "snowy-rainy" -> "Sleet"
    "windy-variant" -> "Windy"
    else -> c.replaceFirstChar { it.uppercase() }
}

@Composable
fun WeatherWidget(backdrop: Backdrop, entity: HaEntity?, forecast: List<JsonObject>, modifier: Modifier = Modifier) {
    if (entity == null) return
    val temp = (entity.attributes["temperature"] as? JsonPrimitive)?.doubleOrNull
    val unit = entity.attr("temperature_unit") ?: "°"
    GlassSurface(backdrop, modifier, cornerRadius = 32.dp) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(entity.state), null, tint = Color.White, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Text(temp?.let { "${it.roundToInt()}°" } ?: "–", style = GlassText.copy(fontSize = 52.sp, fontWeight = FontWeight.Light))
            }
            val today = forecast.firstOrNull()
            val hiLo = today?.let {
                val hi = (it["temperature"] as? JsonPrimitive)?.doubleOrNull?.roundToInt()
                val lo = (it["templow"] as? JsonPrimitive)?.doubleOrNull?.roundToInt()
                if (hi != null && lo != null) "  H $hi°  L $lo°" else ""
            } ?: ""
            Text(conditionLabel(entity.state) + hiLo, style = GlassText.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium))
            if (forecast.size > 1) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    forecast.drop(1).take(4).forEach { day ->
                        val dt = (day["datetime"] as? JsonPrimitive)?.contentOrNull
                        val label = dt?.let { runCatching { OffsetDateTime.parse(it).dayOfWeek }.getOrNull() ?: runCatching { LocalDate.parse(it.take(10)).dayOfWeek }.getOrNull() }
                            ?.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) ?: ""
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = GlassText.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)))
                            Icon(weatherIcon((day["condition"] as? JsonPrimitive)?.contentOrNull), null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Text("${(day["temperature"] as? JsonPrimitive)?.doubleOrNull?.roundToInt() ?: "–"}°", style = GlassText.copy(fontSize = 14.sp))
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ photo info

@Composable
fun PhotoInfoWidget(backdrop: Backdrop, asset: Asset?, modifier: Modifier = Modifier) {
    if (asset == null) return
    val date = asset.takenAt?.let { raw ->
        runCatching { OffsetDateTime.parse(raw).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
    }?.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
    val place = listOfNotNull(asset.city, asset.country?.takeIf { asset.city == null } ?: asset.state).distinct().joinToString(", ")
    val primary = asset.memoryLabel ?: date
    val secondary = listOfNotNull(place.ifBlank { null }, if (asset.memoryLabel != null) date else null).joinToString(" · ")
    if (primary == null && secondary.isBlank()) return
    AnimatedContent(asset.id, transitionSpec = { fadeIn(tween(800)) togetherWith fadeOut(tween(800)) }, modifier = modifier, label = "photoinfo") { _ ->
        GlassSurface(backdrop, cornerRadius = 22.dp) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (asset.memoryLabel != null) Icons.Rounded.History else Icons.Rounded.Place, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.widthIn(max = 420.dp)) {
                    primary?.let { Text(it, style = GlassText.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (secondary.isNotBlank()) Text(secondary, style = GlassText.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    asset.description?.let { Text(it, style = GlassText.copy(fontSize = 14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ controls

fun entityIcon(e: HaEntity): ImageVector = when (e.domain) {
    "light" -> if (e.state == "on") Icons.Rounded.Lightbulb else Icons.Rounded.LightbulbCircle
    "switch", "input_boolean" -> if (e.state == "on") Icons.Rounded.ToggleOn else Icons.Rounded.ToggleOff
    "fan" -> Icons.Rounded.Air
    "cover" -> Icons.Rounded.Window
    "scene" -> Icons.Rounded.Palette
    "script" -> Icons.Rounded.PlayArrow
    "media_player" -> Icons.Rounded.MusicNote
    "climate" -> Icons.Rounded.Thermostat
    "lock" -> if (e.state == "locked") Icons.Rounded.Lock else Icons.Rounded.LockOpen
    else -> Icons.Rounded.Bolt
}

private val activeStates = setOf("on", "open", "playing", "unlocked", "heat", "cool", "heat_cool", "auto")

@Composable
fun ControlTile(backdrop: Backdrop, entity: HaEntity, onClick: () -> Unit) {
    val active = entity.state in activeStates
    val glow by animateFloatAsState(if (active) 1f else 0f, tween(300), label = "glow")
    GlassSurface(
        backdrop,
        Modifier.size(width = 132.dp, height = 104.dp).clickable(onClick = onClick),
        cornerRadius = 26.dp,
        tint = Color(0xFFFFD27A).copy(alpha = 0.28f * glow).compositeOverIfAny(Color.Black.copy(alpha = 0.12f * (1 - glow))),
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(entityIcon(entity), null, tint = if (active) Color(0xFFFFE3A3) else Color.White, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(12.dp))
            Text(entity.name, style = GlassText.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stateLabel(entity), style = GlassText.copy(fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f)), maxLines = 1)
        }
    }
}

private fun Color.compositeOverIfAny(other: Color): Color = if (alpha > 0.001f) this else other

private fun stateLabel(e: HaEntity): String = when {
    e.domain == "climate" -> (e.attributes["current_temperature"] as? JsonPrimitive)?.doubleOrNull?.let { "${it.roundToInt()}° · ${e.state}" } ?: e.state
    e.domain == "light" && e.state == "on" -> (e.attributes["brightness"] as? JsonPrimitive)?.doubleOrNull?.let { "${(it / 2.55).roundToInt()}%" } ?: "On"
    e.domain in setOf("scene", "script") -> "Tap to run"
    else -> e.state.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun GlassCircleButton(backdrop: Backdrop, icon: ImageVector, size: Int = 72, tint: Color = Color.Black.copy(alpha = 0.12f), onClick: () -> Unit) {
    GlassSurface(backdrop, Modifier.size(size.dp).clickable(onClick = onClick), cornerRadius = (size / 2).dp, shape = CircleShape, tint = tint) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size((size * 0.45f).dp))
    }
}

// ------------------------------------------------------------------ assist

@Composable
fun AssistOverlay(backdrop: Backdrop, ui: AssistUi, onTap: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(backdrop, modifier.animateContentSize().widthIn(min = 320.dp, max = 720.dp).clickable(onClick = onTap), cornerRadius = 36.dp, tint = Color.Black.copy(alpha = 0.22f)) {
        Row(Modifier.padding(horizontal = 26.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            when (ui.phase) {
                AssistPhase.LISTENING -> Waveform(ui.level)
                AssistPhase.PROCESSING -> ThinkingDots()
                AssistPhase.ERROR -> Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFFFB4A8), modifier = Modifier.size(36.dp))
                else -> Icon(Icons.Rounded.RecordVoiceOver, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(20.dp))
            Column {
                val title = when (ui.phase) {
                    AssistPhase.LISTENING -> if (ui.transcript.isBlank()) "Listening…" else ui.transcript
                    AssistPhase.ERROR -> ui.error ?: "Something went wrong"
                    else -> ui.transcript.ifBlank { "…" }
                }
                Text(title, style = GlassText.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium))
                if (ui.response.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(ui.response, style = GlassText.copy(fontSize = 19.sp, color = Color.White.copy(alpha = 0.9f)), maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Waveform(level: Float) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(900), RepeatMode.Restart), label = "phase")
    val lv by animateFloatAsState(level, tween(90), label = "lv")
    Canvas(Modifier.size(width = 56.dp, height = 40.dp)) {
        val bars = 5
        val bw = size.width / (bars * 2 - 1)
        for (i in 0 until bars) {
            val wobble = (kotlin.math.sin(phase + i * 1.1f) + 1f) / 2f
            val h = size.height * (0.18f + 0.82f * (0.25f * wobble + 0.75f * lv * (0.6f + 0.4f * wobble)))
            drawRoundRect(Color.White, Offset(i * 2 * bw, (size.height - h) / 2), Size(bw, h), CornerRadius(bw / 2))
        }
    }
}

@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200)), label = "p")
    Canvas(Modifier.size(width = 56.dp, height = 40.dp)) {
        for (i in 0 until 3) {
            val a = 0.35f + 0.65f * (1f - kotlin.math.min(1f, kotlin.math.abs(p - i - 0.5f)))
            drawCircle(Color.White.copy(alpha = a), radius = size.width / 10, center = Offset(size.width * (0.2f + i * 0.3f), size.height / 2))
        }
    }
}

// ------------------------------------------------------------------ toast + pairing

fun toastIcon(name: String?): ImageVector {
    val n = name?.removePrefix("mdi:") ?: return Icons.Rounded.Notifications
    return when {
        n.startsWith("bell") -> Icons.Rounded.NotificationsActive
        n.startsWith("door") -> Icons.Rounded.MeetingRoom
        n.startsWith("cake") || n.startsWith("party") -> Icons.Rounded.Cake
        n.startsWith("heart") -> Icons.Rounded.Favorite
        n.startsWith("phone") -> Icons.Rounded.Phone
        n.startsWith("package") || n.startsWith("truck") -> Icons.Rounded.LocalShipping
        n.startsWith("weather") -> Icons.Rounded.WbSunny
        n.startsWith("alert") -> Icons.Rounded.Warning
        n.startsWith("message") || n.startsWith("chat") -> Icons.Rounded.Chat
        n.startsWith("pill") || n.startsWith("medic") -> Icons.Rounded.Medication
        n.startsWith("calendar") -> Icons.Rounded.Event
        else -> Icons.Rounded.Info
    }
}

@Composable
fun ToastCard(backdrop: Backdrop, toast: ToastMessage, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(backdrop, modifier.widthIn(min = 360.dp, max = 760.dp).clickable(onClick = onDismiss), cornerRadius = 30.dp, tint = Color.Black.copy(alpha = 0.22f)) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(toastIcon(toast.icon), null, tint = Color.White, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(18.dp))
            Column {
                toast.title?.let { Text(it, style = GlassText.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)) }
                Text(toast.message, style = GlassText.copy(fontSize = 20.sp))
            }
        }
    }
}

@Composable
fun PairingCard(backdrop: Backdrop, code: String, modifier: Modifier = Modifier) {
    GlassSurface(backdrop, modifier, cornerRadius = 40.dp, tint = Color.Black.copy(alpha = 0.3f)) {
        Column(Modifier.padding(horizontal = 56.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Home, null, tint = Color.White, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("Home Assistant wants to connect", style = GlassText.copy(fontSize = 24.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(18.dp))
            Text(code.chunked(3).joinToString("  "), style = GlassText.copy(fontSize = 72.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp))
            Spacer(Modifier.height(8.dp))
            Text("Enter this code in Home Assistant", style = GlassText.copy(fontSize = 18.sp, color = Color.White.copy(alpha = 0.85f)))
        }
    }
}
