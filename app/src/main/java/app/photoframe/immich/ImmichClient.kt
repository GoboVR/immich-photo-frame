package app.photoframe.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate

data class Asset(
    val id: String,
    val takenAt: String?,
    val city: String?,
    val state: String?,
    val country: String?,
    val description: String?,
    val people: List<String>,
    val width: Int?,
    val height: Int?,
    /** e.g. "3 years ago" for memories */
    val memoryLabel: String? = null,
    val isVideo: Boolean = false,
    val durationSec: Double? = null,
)

data class Album(val id: String, val name: String, val count: Int)
data class Person(val id: String, val name: String)

class ImmichException(msg: String) : Exception(msg)

class ImmichClient(private val http: OkHttpClient, private val baseUrlProvider: () -> String, private val keyProvider: () -> String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    val baseUrl: String get() = baseUrlProvider().trimEnd('/').removeSuffix("/api")
    val apiKey: String get() = keyProvider()
    val configured: Boolean get() = baseUrlProvider().isNotBlank() && keyProvider().isNotBlank()

    /** Transcoded (if Immich made one) or original video; needs the x-api-key header. */
    fun videoUrl(assetId: String): String = "$baseUrl/api/assets/$assetId/video/playback"

    fun imageUrl(assetId: String, full: Boolean): String =
        if (full) "$baseUrl/api/assets/$assetId/thumbnail?size=fullsize"
        else "$baseUrl/api/assets/$assetId/thumbnail?size=preview"

    private suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement = withContext(Dispatchers.IO) {
        if (!configured) throw ImmichException("Immich not configured")
        val req = Request.Builder()
            .url("$baseUrl/api$path")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .method(method, body?.toString()?.toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ImmichException("HTTP ${resp.code} on $path: ${text.take(200)}")
            if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
        }
    }

    suspend fun ping(): String {
        val about = call("GET", "/server/about").jsonObject
        return about.str("version") ?: "ok"
    }

    suspend fun albums(): List<Album> {
        val own = call("GET", "/albums").jsonArray
        val shared = runCatching { call("GET", "/albums?shared=true").jsonArray }.getOrDefault(JsonArray(emptyList()))
        return (own + shared).map { it.jsonObject }.distinctBy { it.str("id") }.mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            Album(id, o.str("albumName") ?: "Untitled", o["assetCount"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0)
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun people(): List<Person> {
        val res = call("GET", "/people?withHidden=false&size=500").jsonObject
        return res["people"]?.jsonArray.orEmpty().map { it.jsonObject }
            .mapNotNull { o -> val n = o.str("name"); val id = o.str("id"); if (n.isNullOrBlank() || id == null) null else Person(id, n) }
    }

    /** All images in an album (paged via search/metadata). */
    suspend fun albumAssets(albumId: String, max: Int = 2000): List<Asset> =
        searchMetadata(max) { putJsonArray("albumIds") { add(JsonPrimitive(albumId)) } }

    suspend fun personAssets(personId: String, max: Int = 1000): List<Asset> =
        searchMetadata(max) { putJsonArray("personIds") { add(JsonPrimitive(personId)) } }

    private suspend fun searchMetadata(max: Int, filter: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<Asset> {
        val out = mutableListOf<Asset>()
        var page = 1
        while (out.size < max) {
            val body = buildJsonObject {
                filter()
                put("withExif", true)
                put("withPeople", true)
                put("size", 250)
                put("page", page)
            }
            val res = call("POST", "/search/metadata", body).jsonObject
            val assets = res["assets"]?.jsonObject
            val items = assets?.get("items")?.jsonArray.orEmpty()
            out += items.mapNotNull { parseAsset(it.jsonObject) }
            val next = assets?.get("nextPage")?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (next.isNullOrBlank() || items.isEmpty()) break
            page = next.toIntOrNull() ?: (page + 1)
        }
        return out
    }

    suspend fun random(count: Int = 50): List<Asset> {
        val body = buildJsonObject {
            put("size", count); put("withExif", true); put("withPeople", true)
        }
        return call("POST", "/search/random", body).jsonArray.mapNotNull { parseAsset(it.jsonObject) }
    }

    /** "On this day" memories. Tries the modern /memories API, falls back to legacy memory-lane. */
    suspend fun memories(): List<Asset> {
        val today = LocalDate.now()
        val thisYear = today.year
        runCatching {
            val res = call("GET", "/memories?for=${today}T12:00:00.000Z").jsonArray
            val out = res.flatMap { m ->
                val mo = m.jsonObject
                val year = mo["data"]?.jsonObject?.get("year")?.let { (it as? JsonPrimitive)?.intOrNull }
                val label = year?.let { yearsAgo(thisYear - it) }
                mo["assets"]?.jsonArray.orEmpty().mapNotNull { parseAsset(it.jsonObject)?.copy(memoryLabel = label) }
            }
            return out
        }
        return runCatching {
            call("GET", "/assets/memory-lane?day=${today.dayOfMonth}&month=${today.monthValue}").jsonArray.flatMap { m ->
                val mo = m.jsonObject
                val ago = mo["yearsAgo"]?.let { (it as? JsonPrimitive)?.intOrNull }
                mo["assets"]?.jsonArray.orEmpty().mapNotNull { parseAsset(it.jsonObject)?.copy(memoryLabel = ago?.let(::yearsAgo)) }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun asset(id: String): Asset? = runCatching { parseAsset(call("GET", "/assets/$id").jsonObject) }.getOrNull()

    private fun yearsAgo(n: Int) = if (n == 1) "1 year ago" else "$n years ago"

    private fun parseAsset(o: JsonObject): Asset? {
        val id = o.str("id") ?: return null
        val type = o.str("type")
        if (type != "IMAGE" && type != "VIDEO") return null
        if (o["isTrashed"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) return null
        val exif = o["exifInfo"] as? JsonObject
        val people = (o["people"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name")?.takeIf { n -> n.isNotBlank() } }
        var w = exif?.get("exifImageWidth")?.let { (it as? JsonPrimitive)?.intOrNull }
        var h = exif?.get("exifImageHeight")?.let { (it as? JsonPrimitive)?.intOrNull }
        val orientation = exif?.str("orientation")
        if (orientation in setOf("5", "6", "7", "8") && w != null && h != null) { val t = w; w = h; h = t }
        return Asset(
            id = id,
            takenAt = exif?.str("dateTimeOriginal") ?: o.str("localDateTime") ?: o.str("fileCreatedAt"),
            city = exif?.str("city"),
            state = exif?.str("state"),
            country = exif?.str("country"),
            description = exif?.str("description")?.takeIf { it.isNotBlank() },
            people = people,
            width = w, height = h,
            isVideo = type == "VIDEO",
            durationSec = o.str("duration")?.let(::parseDuration),
        )
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Immich durations look like "0:00:12.345000" (H:MM:SS.ffffff). */
internal fun parseDuration(s: String): Double? {
    val parts = s.split(':')
    if (parts.size != 3) return null
    val h = parts[0].toDoubleOrNull() ?: return null
    val m = parts[1].toDoubleOrNull() ?: return null
    val sec = parts[2].toDoubleOrNull() ?: return null
    return h * 3600 + m * 60 + sec
}
