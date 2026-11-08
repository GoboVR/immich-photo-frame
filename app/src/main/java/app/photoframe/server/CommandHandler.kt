package app.photoframe.server

import app.photoframe.AppGraph
import app.photoframe.assist.WakeWordEngine
import app.photoframe.core.ToastMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

class CommandException(msg: String) : Exception(msg)

/** Executes protocol commands (see docs/PROTOCOL.md). */
class CommandHandler(private val g: AppGraph) {

    suspend fun execute(command: String, p: JsonObject): JsonElement {
        when (command) {
            "screen_on" -> g.screen.turnOn()
            "screen_off" -> g.screen.turnOff()
            "set_brightness" -> { g.screen.setBrightness(p.int("brightness")); g.screen.turnOn() }
            "set_auto_brightness" -> g.screen.setAutoBrightness(p.bool("enabled"))
            "set_night_mode" -> g.screen.setNightMode(p.bool("enabled"))

            "slideshow_next" -> g.slideshow.next()
            "slideshow_previous" -> g.slideshow.previous()
            "slideshow_play" -> g.slideshow.setPlaying(true)
            "slideshow_pause" -> g.slideshow.setPlaying(false)
            "set_interval" -> { val s = p.int("seconds").coerceIn(5, 3600); g.config.update { it.copy(intervalSec = s) } }
            "set_source" -> {
                val src = p.str("source")
                if (src !in setOf("album", "people", "memories", "random", "mixed")) throw CommandException("invalid source: $src")
                val albums = p.strList("album_ids"); val people = p.strList("person_ids")
                g.config.update { it.copy(source = src, albumIds = albums ?: it.albumIds, personIds = people ?: it.personIds) }
            }
            "show_asset" -> if (!g.slideshow.showAsset(p.str("asset_id"))) throw CommandException("asset not found")
            "reload_sources" -> g.slideshow.reloadSources()

            "media_play" -> {
                // HA sends its own media as a signed relative path; prefix the HA address we can reach.
                val url = p.str("url").let { if (it.startsWith("/")) g.ha.absolute(it) else it }
                if (p.optBool("announce") == true) {
                    // Don't block the caller for the whole announcement.
                    g.scope.launch { runCatching { g.media.announce(url) } }
                } else {
                    g.media.play(
                        url, p.optStr("title"), p.optStr("artist"), p.optStr("album_art"), p.optStr("mime"),
                        mediaType = p.optStr("media_type"),
                        durationSec = p.prim("duration")?.doubleOrNull,
                        refreshSec = p.prim("refresh")?.doubleOrNull,
                    )
                    // Videos/images/cameras should be seen: wake the screen (e.g. doorbell at night).
                    if (g.media.state.value.visual) g.screen.turnOn()
                }
            }
            "media_pause" -> g.media.pause()
            "media_resume" -> g.media.resume()
            "media_stop" -> g.media.stop()
            "media_seek" -> g.media.seek(p.double("position"))
            "set_volume" -> g.media.setVolume(p.double("volume").toFloat())
            "set_mute" -> g.media.setMuted(p.bool("muted"))

            "show_message" -> g.ui.toast.value = ToastMessage(
                title = p.optStr("title"), message = p.str("message"), icon = p.optStr("icon"),
                durationSec = p.optInt("duration") ?: 10,
            )
            "assist_start" -> g.assist.start()
            "set_wake_word" -> {
                val word = p.optStr("wake_word")
                if (word != null && word !in WakeWordEngine.MODELS) throw CommandException("unknown wake word: $word")
                g.config.update { it.copy(wakeWordEnabled = p.bool("enabled"), wakeWord = word ?: it.wakeWord) }
            }
            "set_widgets" -> g.config.update { c ->
                c.copy(
                    showClock = p.optBool("clock") ?: c.showClock,
                    showWeather = p.optBool("weather") ?: c.showWeather,
                    showPhotoInfo = p.optBool("photo_info") ?: c.showPhotoInfo,
                    showControls = p.optBool("controls") ?: c.showControls,
                    weatherEntity = if (p.containsKey("weather_entity")) p.optStr("weather_entity") else c.weatherEntity,
                    controlEntities = p.strList("entities") ?: c.controlEntities,
                    showNowPlaying = p.optBool("now_playing") ?: c.showNowPlaying,
                    nowPlayingEntities = p.strList("now_playing_entities") ?: c.nowPlayingEntities,
                )
            }
            "provision_ha" -> {
                val url = p.str("url").trimEnd('/'); val token = p.str("token")
                val urls = p.strList("urls").orEmpty().map { it.trimEnd('/') }
                g.config.update { it.copy(haUrl = url, haUrls = urls, haToken = token, haActiveUrl = null) }
            }
            else -> throw CommandException("unknown command: $command")
        }
        return JsonNull
    }
}

private fun JsonObject.prim(k: String) = this[k] as? JsonPrimitive
private fun JsonObject.str(k: String) = prim(k)?.contentOrNull ?: throw CommandException("missing param: $k")
private fun JsonObject.optStr(k: String) = prim(k)?.contentOrNull
private fun JsonObject.int(k: String) = prim(k)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: throw CommandException("missing int param: $k")
private fun JsonObject.optInt(k: String) = prim(k)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
private fun JsonObject.double(k: String) = prim(k)?.doubleOrNull ?: throw CommandException("missing number param: $k")
private fun JsonObject.bool(k: String) = prim(k)?.booleanOrNull ?: throw CommandException("missing bool param: $k")
private fun JsonObject.optBool(k: String) = prim(k)?.booleanOrNull
private fun JsonObject.strList(k: String): List<String>? = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
