package com.meenai.mina.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Home 的常驻天气卡:自动定位(Fused,balanced;无 GMS 时退回网络定位)+ 反地理编码取地名,
 * open-meteo 免密钥拿实时温度/天气代码/日出日落,结果存进 DataStore,启动先把缓存画出来再原地淡换。
 * 30 分钟一刷;定位和上次差过 5 公里就立刻重取。
 */
data class WeatherData(
    val place: String,
    val temp: Double,
    /** WMO 天气代码。 */
    val code: Int,
    /** 白天 1 / 夜里 0,选图标用。 */
    val isDay: Boolean,
    /** 今天日落,open-meteo 的本地时刻 "2026-11-10T17:34"。 */
    val sunset: String?,
    /** 下一次日出(早上是今天,日落后是明天)。 */
    val nextSunrise: String?,
    /** 今天的日出,判断现在在哪一段用。 */
    val todaySunrise: String?,
    val lat: Double,
    val lon: Double,
    /** 取数据的时刻。 */
    val at: Long,
)

private val Context.homeWeatherStore by preferencesDataStore(name = "home_weather")
private val WEATHER_JSON = stringPreferencesKey("weather_json")

class WeatherRepository(private val context: Context) {

    suspend fun cached(): WeatherData? = readCache()

    /** 现在是否已授权精确定位(Coarse 也算:balananced 精度本来就是大致位置)。 */
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 刷新:先看缓存,新鲜(半小时内)且没挪远(5 公里内)就直接用;
     * 拿到新定位、或缓存太旧、或缓存为空才重新取。[force] 跳过新鲜度检查(授权后的第一次刷新)。
     */
    suspend fun refresh(force: Boolean = false): WeatherData? = withContext(Dispatchers.IO) {
        val old = readCache()
        if (!force && old != null && System.currentTimeMillis() - old.at < STALE_MS) return@withContext old
        val loc = if (hasLocationPermission()) currentLocation() else null
        when {
            loc != null && old != null && !force && distance(old.lat, old.lon, loc) < MOVE_METERS && System.currentTimeMillis() - old.at < STALE_MS -> old
            loc != null -> fetch(loc.latitude, loc.longitude)
            old != null -> old
            else -> null
        }
    }

    /** 定位:Fused balanced;系统里没有 GMS 或超时就退回网络最后位置。 */
    private suspend fun currentLocation(): Location? {
        val fused = runCatching {
            LocationServices.getFusedLocationProviderClient(context)
        }.getOrNull() ?: return lastKnown()
        return runCatching {
            Tasks.await(fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token), 10_000L, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: lastKnown()
    }

    private fun lastKnown(): Location? = runCatching {
        val lm = context.getSystemService(LocationManager::class.java)
        lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
    }.getOrNull()

    /** 地名:小区/区 > 城 > 县 > 省,加上国家码,像 "Lachhmangarh Tehsil, IN"。 */
    private fun geocode(lat: Double, lon: Double): String {
        val g = runCatching { Geocoder(context, Locale.getDefault()) }.getOrNull() ?: return ""
        if (!Geocoder.isPresent()) return ""
        val addr = runCatching { g.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull() ?: return ""
        val name = addr.subAdminArea ?: addr.locality ?: addr.subLocality ?: addr.adminArea ?: addr.countryName.orEmpty()
        val cc = addr.countryCode
        return when {
            name.isBlank() -> ""
            cc.isNullOrBlank() -> name
            else -> "$name, $cc"
        }
    }

    private suspend fun fetch(lat: Double, lon: Double): WeatherData? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=sunrise,sunset&forecast_days=2&timezone=auto"
        val text = get(url) ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val cur = root.optJSONObject("current") ?: return null
        val temp = cur.optDouble("temperature_2m", Double.NaN).takeIf { !it.isNaN() } ?: return null
        val code = cur.optInt("weather_code", 0)
        val isDay = cur.optInt("is_day", 1) == 1
        val daily = root.optJSONObject("daily")
        fun arr(key: String): List<String>? = daily?.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it, "") } }
        val sunrises = arr("sunrise")
        val sunsets = arr("sunset")
        val data = WeatherData(
            place = geocode(lat, lon),
            temp = temp, code = code, isDay = isDay,
            sunset = sunsets?.getOrNull(0)?.takeIf { it.isNotBlank() },
            nextSunrise = if (sunrises != null && sunrises.size > 1) sunrises[1] else sunrises?.getOrNull(0),
            todaySunrise = sunrises?.getOrNull(0)?.takeIf { it.isNotBlank() },
            lat = lat, lon = lon, at = System.currentTimeMillis(),
        )
        saveCache(data)
        return data
    }

    private suspend fun saveCache(d: WeatherData) {
        val o = JSONObject()
            .put("place", d.place).put("temp", d.temp).put("code", d.code)
            .put("isDay", d.isDay).put("sunset", d.sunset.orEmpty()).put("nextSunrise", d.nextSunrise.orEmpty())
            .put("todaySunrise", d.todaySunrise.orEmpty())
            .put("lat", d.lat).put("lon", d.lon).put("at", d.at)
        context.homeWeatherStore.edit { it[WEATHER_JSON] = o.toString() }
    }

    private suspend fun readCache(): WeatherData? {
        val raw = context.homeWeatherStore.data.first()[WEATHER_JSON] ?: return null
        return runCatching {
            val o = JSONObject(raw)
            WeatherData(
                place = o.optString("place"), temp = o.getDouble("temp"), code = o.getInt("code"),
                isDay = o.getBoolean("isDay"), sunset = o.optString("sunset").ifBlank { null },
                nextSunrise = o.optString("nextSunrise").ifBlank { null },
                todaySunrise = o.optString("todaySunrise").ifBlank { null },
                lat = o.getDouble("lat"), lon = o.getDouble("lon"), at = o.getLong("at"),
            )
        }.getOrNull()
    }

    private fun distance(lat1: Double, lon1: Double, l2: Location): Float {
        val res = FloatArray(1)
        Location.distanceBetween(lat1, lon1, l2.latitude, l2.longitude, res)
        return res[0]
    }

    private fun get(url: String): String? = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        c.inputStream.bufferedReader().use { it.readText() }
    }.onFailure { it.printStackTrace() }.getOrNull()

    companion object {
        /** 30 分钟内不重取。 */
        const val STALE_MS = 30 * 60_000L
        /** 定位挪过这么远就立刻重取。 */
        const val MOVE_METERS = 5_000f
    }
}

/** 日落/日出现在属于哪段:日出前和日落后都显示日出,白天显示日落。 */
enum class SunPhase { Sunset, Sunrise }

fun sunPhase(w: WeatherData, now: LocalDateTime): SunPhase {
    val rise = w.todaySunrise?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val set = w.sunset?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    return when {
        rise != null && now < rise -> SunPhase.Sunrise
        set != null && now < set -> SunPhase.Sunset
        else -> SunPhase.Sunrise
    }
}
