package org.handtop.desktop.widgets.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    /** The approximate location, or null (no permission, location off, no fix): last known, or one new fix. */
    @SuppressLint("MissingPermission")
    fun locate(context: Context, onResult: (Location?) -> Unit) {
        if (!allowed(context)) return onResult(null)
        val lm = context.getSystemService(LocationManager::class.java) ?: return onResult(null)
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < FRESH_MS) return onResult(last)
        if (!runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) return onResult(last)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, context.mainExecutor) { onResult(it ?: last) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, object : LocationListener {
                    override fun onLocationChanged(location: Location) = onResult(location)
                }, Looper.getMainLooper())
            }
        } catch (_: Exception) {
            onResult(last)
        }
    }

    /** The town at [lat], [lon] in the phone's language, or null. */
    fun placeName(context: Context, lat: Double, lon: Double, onResult: (String?) -> Unit) {
        if (!Geocoder.isPresent()) return onResult(null)
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
