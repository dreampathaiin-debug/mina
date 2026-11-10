package com.meenai.mina.home

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import com.meenai.mina.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Home 的面板:一张实时天气卡,和交给 agent 留意的事。
 * 全部存在私有目录 home/panels.json,简单、可丢。
 */
data class HomePanel(
    val id: String,
    /** "weather" 或 "track"。 */
    val kind: String,
    /** weather 存城市名;track 存要留意的事。 */
    val name: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val country: String? = null,
    val temp: Double? = null,
    /** WMO 天气代码。 */
    val code: Int? = null,
    val sunset: String? = null,
    val updatedAt: Long = 0,
)

class PanelsStore(context: Context) {
    private val dir = File(context.filesDir, "home")
    private val file = File(dir, "panels.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<HomePanel> = synchronized(lock) { read() }

    fun put(panel: HomePanel) = synchronized(lock) { write(read().filterNot { it.id == panel.id } + panel) }

    fun remove(id: String) = synchronized(lock) { write(read().filterNot { it.id == id }) }

    private fun read(): List<HomePanel> = runCatching {
        if (!file.isFile) return emptyList()
        json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { row ->
            val o = (row as? JsonObject) ?: return@mapNotNull null
            fun p(key: String) = o[key] as? JsonPrimitive
            val id = p("id")?.contentOrNull ?: return@mapNotNull null
            val kind = p("kind")?.contentOrNull ?: return@mapNotNull null
            val name = p("name")?.contentOrNull ?: return@mapNotNull null
            HomePanel(
                id = id, kind = kind, name = name,
                lat = p("lat")?.doubleOrNull, lon = p("lon")?.doubleOrNull,
                country = p("country")?.contentOrNull,
                temp = p("temp")?.doubleOrNull, code = p("code")?.intOrNull,
                sunset = p("sunset")?.contentOrNull,
                updatedAt = p("updatedAt")?.longOrNull ?: 0L,
            )
        }
    }.getOrDefault(emptyList())

    private fun write(rows: List<HomePanel>) {
        dir.mkdirs()
        val arr = buildJsonArray {
            rows.forEach { p ->
                add(buildJsonObject {
                    put("id", p.id); put("kind", p.kind); put("name", p.name)
                    p.lat?.let { put("lat", it) }; p.lon?.let { put("lon", it) }
                    p.country?.let { put("country", it) }
                    p.temp?.let { put("temp", it) }; p.code?.let { put("code", it) }
                    p.sunset?.let { put("sunset", it) }; put("updatedAt", p.updatedAt)
                })
            }
        }
        file.writeText(arr.toString())
    }

    companion object {
        fun shared(context: Context): PanelsStore = shared ?: PanelsStore(context.applicationContext).also { shared = it }
        @Volatile private var shared: PanelsStore? = null
    }
}

/** open-meteo 的免密钥天气:城市定位 + 实时温度/天气代码/日落。 */
object Weather {
    data class Now(val temp: Double, val code: Int, val sunset: String?)

    fun locate(city: String): Pair<Double, Double>? {
        val text = get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city.trim(), "UTF-8")}&count=1&language=en&format=json") ?: return null
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val first = (root["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val lat = (first["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lon = (first["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return lat to lon
    }

    fun now(lat: Double, lon: Double): Now? {
        val text = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&daily=sunset&forecast_days=1&timezone=auto") ?: return null
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val cur = root["current"] as? JsonObject ?: return null
        val temp = (cur["temperature_2m"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val code = (cur["weather_code"] as? JsonPrimitive)?.intOrNull ?: 0
        val sunset = ((root["daily"] as? JsonObject)?.get("sunset") as? JsonArray)
            ?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
        return Now(temp, code, sunset)
    }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        c.inputStream.bufferedReader().use { it.readText() }
    }.onFailure { it.printStackTrace() }.getOrNull()
}

/** WMO 代码 → 一句天气描述。 */
fun weatherKind(code: Int?): Int = when (code) {
    0 -> R.string.weather_clear
    1, 2, 3 -> R.string.weather_partly
    45, 48 -> R.string.weather_fog
    in 51..57 -> R.string.weather_drizzle
    in 61..67, in 80..82 -> R.string.weather_rain
    in 71..77, 85, 86 -> R.string.weather_snow
    in 95..99 -> R.string.weather_thunder
    else -> R.string.weather_cloudy
}
