package com.colorfit.companion.vendor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.colorfit.companion.data.GattDumper
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the next three days of weather from the free Open-Meteo API
 * (no API key, no rate limits for personal use) and pushes them to the
 * watch via [VendorConnection].
 *
 * Usage:
 *   weather.setLocation(latitude = 28.61, longitude = 77.21) // Delhi
 *   weather.refreshAndPush()                                  // fetch + push
 *
 * Open-Meteo WMO weather codes are mapped to the Noise `conditionCode`
 * via [WeatherConditionCode.fromWmo].
 */
@Singleton
class WeatherService @Inject constructor(
    private val vendor: VendorConnection,
    @ApplicationContext private val context: Context,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _location = MutableStateFlow(loadSavedLocation())
    val location: StateFlow<WeatherLocation?> = _location.asStateFlow()

    private val _lastFetch = MutableStateFlow<Long?>(null)
    val lastFetch: StateFlow<Long?> = _lastFetch.asStateFlow()

    /**
     * Human-readable outcome of the last weather action, for the UI. Without
     * this every failure was invisible — it only went to Timber, so a failed
     * fetch or push looked identical to doing nothing.
     */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun setLocation(lat: Double, lon: Double, label: String = "") {
        val loc = WeatherLocation(lat, lon, label)
        _location.value = loc
        prefs.edit()
            .putFloat(PREF_LAT, lat.toFloat())
            .putFloat(PREF_LON, lon.toFloat())
            .putString(PREF_LABEL, label)
            .apply()
    }

    /** Restore the location chosen in a previous session. */
    private fun loadSavedLocation(): WeatherLocation? {
        if (!prefs.contains(PREF_LAT) || !prefs.contains(PREF_LON)) return null
        return WeatherLocation(
            latitude = prefs.getFloat(PREF_LAT, 0f).toDouble(),
            longitude = prefs.getFloat(PREF_LON, 0f).toDouble(),
            label = prefs.getString(PREF_LABEL, "") ?: "",
        )
    }

    /**
     * True if the app currently has the runtime permission needed to read
     * the device GPS. UI should call this and route the "Use my location"
     * button through [requestDeviceLocation] when it returns false.
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Use the device's last known GPS / network location as the weather
     * source. Returns the resolved [Location] on success, `null` if no
     * provider has a fix yet, or throws [SecurityException] if the
     * permission is missing — UI should call [hasLocationPermission] first.
     */
    @SuppressLint("MissingPermission")
    fun getDeviceLocation(): Location? {
        if (!hasLocationPermission()) {
            throw SecurityException("ACCESS_FINE_LOCATION not granted")
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        // Try the providers most likely to have a recent fix, in order.
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers
            .mapNotNull { p ->
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }

    /**
     * Use the device's GPS as the weather source and immediately push
     * the current weather. Returns true on success.
     *
     * If you want a one-shot location request (waits for a fresh fix),
     * use [requestDeviceLocation] from a Composable that handles the
     * permission grant. This variant just uses the cached fix.
     */
    suspend fun useDeviceLocation(): Boolean = withContext(Dispatchers.IO) {
        val result = runCatching { getDeviceLocation() }
        val loc = result.getOrNull()
        if (loc == null) {
            Timber.tag(TAG).w("useDeviceLocation: no fix available", result.exceptionOrNull())
            _status.value = if (result.exceptionOrNull() is SecurityException) {
                "Location permission not granted."
            } else {
                "No location fix available yet — open a maps app to get one, or enter a city instead."
            }
            return@withContext false
        }
        val name = reverseGeocode(loc.latitude, loc.longitude) ?: "My location"
        setLocation(loc.latitude, loc.longitude, name)
        refreshAndPush()
    }

    /**
     * Look up a city's coordinates via Open-Meteo's free geocoding API
     * and push weather for the first match. Returns true on success.
     *
     * Free, no API key, accurate enough for personal use.
     */
    suspend fun useCityName(cityName: String): Boolean = withContext(Dispatchers.IO) {
        val q = cityName.trim()
        if (q.isEmpty()) return@withContext false
        try {
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                java.net.URLEncoder.encode(q, "UTF-8") +
                "&count=1&language=en&format=json"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.tag(TAG).w("geocoding HTTP ${resp.code}")
                    return@withContext false
                }
                val body = resp.body?.string() ?: return@withContext false
                val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
                    ?: return@withContext false
                if (results.isEmpty()) {
                    Timber.tag(TAG).w("geocoding: no results for '$q'")
                    _status.value = "No place found matching \"$q\"."
                    return@withContext false
                }
                val first = results[0].jsonObject
                val lat = first["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@withContext false
                val lon = first["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@withContext false
                val name = first["name"]?.jsonPrimitive?.content ?: q
                val country = first["country"]?.jsonPrimitive?.content
                val label = if (country != null) "$name, $country" else name
                setLocation(lat, lon, label)
                refreshAndPush()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "geocoding failed")
            false
        }
    }

    /**
     * Fetch weather and push it. Returns true if a push was sent, false on
     * any failure (network, parse, missing location, vendor not ready).
     */
    suspend fun refreshAndPush(): Boolean = withContext(Dispatchers.IO) {
        val loc = _location.value ?: run {
            Timber.tag(TAG).w("refreshAndPush: no location set")
            _status.value = "Set a location first — look up a city, use your location, or save a lat/lon."
            return@withContext false
        }
        _status.value = "Fetching weather for ${loc.describe()}…"
        try {
            val url = buildUrl(loc)
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.tag(TAG).w("Open-Meteo HTTP ${resp.code}")
                    _status.value = "Weather service returned HTTP ${resp.code}."
                    return@withContext false
                }
                val body = resp.body?.string() ?: run {
                    _status.value = "Weather service returned an empty response."
                    return@withContext false
                }
                val days = parse(body)
                if (days.isEmpty()) {
                    Timber.tag(TAG).w("Open-Meteo: no forecast days parsed")
                    _status.value = "Couldn't read the forecast from the weather service."
                    return@withContext false
                }
                // pushWeather returns false when there's no watch to write to.
                // The watch has room for a short place name; drop the country.
                val pushed = vendor.pushWeather(days, cityName = loc.label.substringBefore(',').trim())
                _lastFetch.value = System.currentTimeMillis()
                _status.value = if (pushed) {
                    "Pushed ${days.size}-day forecast for ${loc.describe()} to the watch."
                } else {
                    "Got the forecast, but the watch isn't connected — nothing was pushed."
                }
                pushed
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Open-Meteo fetch failed")
            _status.value = "Weather fetch failed: ${e.message ?: e::class.simpleName}"
            false
        }
    }

    private fun buildUrl(loc: WeatherLocation): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${loc.latitude}" +
            "&longitude=${loc.longitude}" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_direction_10m,wind_speed_10m,visibility" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,uv_index_max" +
            // The watch renders a 7-day card (0xCB packets 0x01/0x02/0x03).
            "&forecast_days=7" +
            "&timezone=auto"

    private fun parse(body: String): List<DailyWeather> {
        val root = json.parseToJsonElement(body).jsonObject
        val current = root["current"]?.jsonObject
        val daily = root["daily"]?.jsonObject ?: return emptyList()

        val currentTemp = current?.get("temperature_2m")?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
        val currentCode = current?.get("weather_code")?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
        val humidity = current?.get("relative_humidity_2m")?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
        val windDir = current?.get("wind_direction_10m")?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
        val windSpeed = current?.get("wind_speed_10m")?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
        val visibilityM = current?.get("visibility")?.jsonPrimitive?.doubleOrNull?.toInt() ?: 10000
        val uvIndex = daily["uv_index_max"]?.jsonArray?.firstOrNull()
            ?.jsonPrimitive?.doubleOrNull?.toInt()?.coerceIn(0, 15) ?: 0

        val codes = daily["weather_code"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull?.toInt() } ?: return emptyList()
        val maxTemps = daily["temperature_2m_max"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull?.toInt() } ?: return emptyList()
        val minTemps = daily["temperature_2m_min"]?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull?.toInt() } ?: return emptyList()

        // Build the 3-day list: today (with current temp), then 2 forecast days
        val days = mutableListOf<DailyWeather>()
        // Today
        if (codes.isNotEmpty()) {
            days.add(
                DailyWeather(
                    conditionCode = WeatherConditionCode.fromWmo(codes[0]),
                    currentTempC = currentTemp,
                    maxTempC = maxTemps.getOrElse(0) { currentTemp },
                    minTempC = minTemps.getOrElse(0) { currentTemp },
                    humidity = humidity,
                    uvIndex = uvIndex,
                    windDirection = (windDir / 45) % 8,
                    windLevel = (windSpeed / 3.4).toInt().coerceIn(0, 12),  // km/h → Beaufort-ish
                    visibilityKm = (visibilityM / 1000).coerceAtMost(99),
                )
            )
        }
        // Remaining forecast days (the watch shows up to 7).
        for (i in 1 until minOf(codes.size, maxTemps.size, minTemps.size, MAX_FORECAST_DAYS)) {
            days.add(
                DailyWeather(
                    conditionCode = WeatherConditionCode.fromWmo(codes[i]),
                    currentTempC = (maxTemps[i] + minTemps[i]) / 2,
                    maxTempC = maxTemps[i],
                    minTempC = minTemps[i],
                    humidity = 0,
                    uvIndex = 0,
                    windDirection = 0,
                    windLevel = 0,
                    visibilityKm = 10,
                )
            )
        }
        return days
    }

    /**
     * Turn GPS coordinates into a human-readable place name using Android's
     * built-in [Geocoder] (no API key — uses the platform backend). Returns
     * e.g. "Mumbai, India", or null when geocoding is unavailable or finds
     * nothing. The GPS path used to hardcode the label to "Device location",
     * so the displayed name never reflected where you actually were even
     * though the coordinates updated.
     */
    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val a = Geocoder(context, Locale.getDefault())
                .getFromLocation(lat, lon, 1)
                ?.firstOrNull()
                ?: return null
            val city = a.locality
                ?: a.subAdminArea
                ?: a.adminArea
                ?: a.featureName
            val country = a.countryName
            when {
                city != null && country != null -> "$city, $country"
                city != null -> city
                else -> country
            }?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "reverseGeocode failed")
            null
        }
    }

    data class WeatherLocation(val latitude: Double, val longitude: Double, val label: String) {
        /** e.g. "Delhi, India (28.6100, 77.2100)" — label plus coordinates. */
        fun describe(): String {
            val coords = "%.4f, %.4f".format(latitude, longitude)
            return if (label.isBlank()) coords else "$label ($coords)"
        }
    }

    companion object {
        private const val TAG = "Weather"

        private const val PREFS_NAME = "colorfit_weather_prefs"
        private const val PREF_LAT = "weather_lat"
        private const val PREF_LON = "weather_lon"
        private const val PREF_LABEL = "weather_label"

        /** The watch's 0xCB packets cover at most a 7-day forecast. */
        private const val MAX_FORECAST_DAYS = 7
    }
}
