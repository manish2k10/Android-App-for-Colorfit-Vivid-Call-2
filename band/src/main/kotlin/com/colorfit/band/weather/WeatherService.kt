package com.colorfit.band.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.colorfit.band.BandSession
import com.colorfit.band.protocol.BandCommands
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Fetches today's weather and a 7-day forecast from Open-Meteo (free, no key)
 * and pushes it to the band — on each connect and every few hours while
 * connected, once a location has been chosen.
 */
@Singleton
class WeatherService @Inject constructor(
    private val session: BandSession,
    @ApplicationContext private val context: Context,
) {
    data class Location(val latitude: Double, val longitude: Double, val label: String) {
        /** Short place name for the band's screen: the part before the country. */
        val city: String get() = label.substringBefore(',').trim()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _location = MutableStateFlow(loadLocation())
    val location: StateFlow<Location?> = _location.asStateFlow()

    /** Outcome of the last weather action, for the UI. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    init {
        scope.launch {
            session.readyEvents.collect { if (_location.value != null) refreshAndPush() }
        }
        scope.launch {
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                if (_location.value != null && session.state.value.ready) refreshAndPush()
            }
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Use the phone's last known position, then push. */
    @SuppressLint("MissingPermission")
    suspend fun useDeviceLocation(): Boolean = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            _status.value = "Location permission not granted."
            return@withContext false
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val fix = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm?.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (fix == null) {
            _status.value = "No location fix yet — open a maps app once, or look up a city instead."
            return@withContext false
        }
        setLocation(Location(fix.latitude, fix.longitude, reverseGeocode(fix.latitude, fix.longitude) ?: "My location"))
        refreshAndPush()
    }

    /** Look the city up with Open-Meteo's geocoding API, then push. */
    suspend fun useCityName(name: String): Boolean = withContext(Dispatchers.IO) {
        val q = name.trim()
        if (q.isEmpty()) return@withContext false
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(q, "UTF-8") + "&count=1&language=en&format=json"
        val root = fetch(url) ?: return@withContext false
        val first = root["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: run {
            _status.value = "No place found matching \"$q\"."
            return@withContext false
        }
        val lat = first["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@withContext false
        val lon = first["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@withContext false
        val city = first["name"]?.jsonPrimitive?.content ?: q
        val country = first["country"]?.jsonPrimitive?.content
        setLocation(Location(lat, lon, if (country != null) "$city, $country" else city))
        refreshAndPush()
    }

    /** Fetch and push. False on any failure, with the reason in [status]. */
    suspend fun refreshAndPush(): Boolean = withContext(Dispatchers.IO) {
        val loc = _location.value ?: run {
            _status.value = "Choose a location first."
            return@withContext false
        }
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${loc.latitude}&longitude=${loc.longitude}" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
            "&forecast_days=7&timezone=auto"
        val root = fetch(url) ?: return@withContext false
        val current = root["current"]?.jsonObject
        val daily = root["daily"]?.jsonObject
        val codes = daily.ints("weather_code")
        val maxes = daily.ints("temperature_2m_max")
        val mins = daily.ints("temperature_2m_min")
        if (codes.isEmpty() || maxes.isEmpty() || mins.isEmpty()) {
            _status.value = "Couldn't read the forecast."
            return@withContext false
        }
        val temp = current?.get("temperature_2m")?.jsonPrimitive?.doubleOrNull?.roundToInt() ?: maxes[0]
        val code = current?.get("weather_code")?.jsonPrimitive?.doubleOrNull?.toInt() ?: codes[0]
        val days = (0 until minOf(codes.size, maxes.size, mins.size, 7)).map {
            BandCommands.Day(bandWeatherId(codes[it]), maxes[it], mins[it])
        }
        val pushed = session.pushWeather(
            weatherId = bandWeatherId(code),
            tempC = temp,
            sunriseMinutes = daily.firstTime("sunrise"),
            sunsetMinutes = daily.firstTime("sunset"),
            days = days,
            city = loc.city,
            latitude = loc.latitude,
            longitude = loc.longitude,
        )
        _status.value = if (pushed) {
            "Sent ${days.size}-day weather for ${loc.label} to the band."
        } else {
            "Got the forecast, but the band isn't connected."
        }
        pushed
    }

    private fun setLocation(loc: Location) {
        _location.value = loc
        prefs.edit()
            .putFloat(PREF_LAT, loc.latitude.toFloat())
            .putFloat(PREF_LON, loc.longitude.toFloat())
            .putString(PREF_LABEL, loc.label)
            .apply()
    }

    private fun loadLocation(): Location? {
        if (!prefs.contains(PREF_LAT) || !prefs.contains(PREF_LON)) return null
        return Location(
            prefs.getFloat(PREF_LAT, 0f).toDouble(),
            prefs.getFloat(PREF_LON, 0f).toDouble(),
            prefs.getString(PREF_LABEL, "") ?: "",
        )
    }

    private fun fetch(url: String): JsonObject? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                _status.value = "Weather service returned HTTP ${resp.code}."
                null
            } else {
                resp.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
            }
        }
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "fetch failed")
        _status.value = "Weather fetch failed: ${e.message ?: e::class.simpleName}"
        null
    }

    private fun JsonObject?.ints(key: String): List<Int> =
        this?.get(key)?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull?.roundToInt() } ?: emptyList()

    /** "2026-10-18T05:33" → minutes after midnight. */
    private fun JsonObject?.firstTime(key: String): Int? {
        val iso = this?.get(key)?.jsonArray?.firstOrNull()?.jsonPrimitive?.content ?: return null
        val hhmm = iso.substringAfter('T', "").split(':')
        val h = hhmm.getOrNull(0)?.toIntOrNull() ?: return null
        val m = hhmm.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            val a = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
                ?: return null
            val city = a.locality ?: a.subAdminArea ?: a.adminArea
            listOfNotNull(city, a.countryName).joinToString(", ").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "BandWeather"
        private const val PREFS_NAME = "colorfit_band_weather"
        private const val PREF_LAT = "lat"
        private const val PREF_LON = "lon"
        private const val PREF_LABEL = "label"
        private const val REFRESH_INTERVAL_MS = 3 * 60 * 60_000L

        /**
         * WMO weather code → the band's icon (`CRPWeatherId`: 0 cloudy,
         * 1 foggy, 2 overcast, 3 rainy, 4 snowy, 5 sunny, 6 sandstorm, 7 haze).
         */
        fun bandWeatherId(wmo: Int): Int = when (wmo) {
            0 -> 5
            1, 2 -> 0
            3 -> 2
            45, 48 -> 1
            in 51..67, in 80..82, in 95..99 -> 3
            in 71..77, 85, 86 -> 4
            else -> 0
        }
    }
}
