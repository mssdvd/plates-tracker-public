package com.mssdvd.platestracker.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.abs

/**
 * Keeps the latest GPS fix while capture runs (docs/android-app.md component 6). Sightings read
 * the fix at promotion time; a promotion with no fresh fix is dropped rather than recorded at a
 * made-up location (the server requires lat/lon).
 */
class LocationProvider(context: Context) {

    data class Fix(
        val lat: Double,
        val lon: Double,
        val speedKmh: Double?,
        val accuracyM: Double?,
        val elapsedMs: Long, // SystemClock.elapsedRealtime() of the fix
    )

    private val client = LocationServices.getFusedLocationProviderClient(context)

    @Volatile
    var latest: Fix? = null
        private set

    private val history = ArrayDeque<Fix>() // guarded by itself; ~30 s of 1 Hz fixes

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            val fix = Fix(
                lat = l.latitude,
                lon = l.longitude,
                speedKmh = if (l.hasSpeed()) l.speed * 3.6 else null, // m/s -> km/h
                accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
                elapsedMs = SystemClock.elapsedRealtime(),
            )
            latest = fix
            synchronized(history) {
                history.addLast(fix)
                while (history.size > HISTORY_SIZE) history.removeFirst()
            }
        }
    }

    @SuppressLint("MissingPermission") // caller starts this only after ACCESS_FINE_LOCATION is granted
    fun start() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stop() {
        client.removeLocationUpdates(callback)
        latest = null
        synchronized(history) { history.clear() }
    }

    /** The latest fix if it's recent enough to attach to a sighting, else null. */
    fun fresh(maxAgeMs: Long = 10_000L): Fix? =
        latest?.takeIf { SystemClock.elapsedRealtime() - it.elapsedMs <= maxAgeMs }

    /**
     * The fix closest in time to [elapsedMs], within [toleranceMs]. Burst promotions (capture v2)
     * arrive seconds after the pass — at 25 m/s "the fix of now" would be off by the whole review
     * delay, so sightings are stamped with the fix nearest their first frame instead.
     */
    fun nearest(elapsedMs: Long, toleranceMs: Long = 10_000L): Fix? = synchronized(history) {
        history.minByOrNull { abs(it.elapsedMs - elapsedMs) }
            ?.takeIf { abs(it.elapsedMs - elapsedMs) <= toleranceMs }
    }

    private companion object {
        const val HISTORY_SIZE = 30
    }
}
