package org.handtop.desktop.widgets.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The phone's approximate location (no exact one is asked for) and the name of the place,
 * with Android's own services: no Google Play services, HandTop comes from GitHub.
 */
object WeatherLocation {

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "weather-place").apply { isDaemon = true } }

    /** A last known location this recent is good enough for the weather. */
    private const val FRESH_MS = 3 * 60 * 60_000L

    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Where to ask for the location among the [enabled] providers: the network one; without it
     * (phones without Google services), the fused one or GPS, which coarse access may use since
     * Android 12 (sdk 31).
     */
    fun provider(enabled: Set<String>, sdk: Int): String? = when {
        LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
        sdk >= 31 && "fused" in enabled -> "fused"
        sdk >= 31 && LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
        else -> null
    }

    /**
     * The approximate location, or null (no permission, location off, no fix in 30 seconds):
     * a recent last known one, or one new fix, answered once.
     */
    @SuppressLint("MissingPermission")
    fun locate(context: Context, onResult: (Location?) -> Unit) {
        if (!allowed(context)) return onResult(null)
        val lm = context.getSystemService(LocationManager::class.java) ?: return onResult(null)
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < FRESH_MS) return onResult(last)
        val provider = provider(runCatching { lm.getProviders(true).toSet() }.getOrDefault(emptySet()), Build.VERSION.SDK_INT)
            ?: return onResult(last)
        try {
            LocationManagerCompat.getCurrentLocation(lm, provider, null as CancellationSignal?, ContextCompat.getMainExecutor(context)) {
                onResult(it ?: last)
            }
        } catch (_: Exception) {
            onResult(last)
        }
    }

    /** The town at [lat], [lon] in the phone's language, or null. */
    fun placeName(context: Context, latitude: Double, longitude: Double, onResult: (String?) -> Unit) {
        if (!Geocoder.isPresent()) return onResult(null)
        // Like Open-Meteo, the phone's geocoder (often a server) gets the rounded point only.
        val lat = OpenMeteo.round(latitude)
        val lon = OpenMeteo.round(longitude)
        val geocoder = Geocoder(context, Locale.getDefault())
        val pick = { list: List<Address>? -> list?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    main.post { onResult(pick(addresses)) }
                }

                override fun onError(errorMessage: String?) {
                    main.post { onResult(null) }
                }
            })
        } else {
            executor.execute {
                @Suppress("DEPRECATION")
                val found = runCatching { geocoder.getFromLocation(lat, lon, 1) }.getOrNull()
                main.post { onResult(pick(found)) }
            }
        }
    }
}
