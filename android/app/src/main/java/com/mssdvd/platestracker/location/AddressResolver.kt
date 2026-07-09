package com.mssdvd.platestracker.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Reverse-geocodes sighting coordinates into a short address for the history screen, via the
 * platform [Geocoder] (backed by Play services on Pixels — no API key, no extra dependency).
 * The lookup itself does leave the device, so results are cached (~11 m grid) and only requested
 * when the user actually opens a plate's history; callers fall back to raw coordinates on null.
 */
object AddressResolver {

    private val cache = ConcurrentHashMap<String, String>()

    /** Calls back on the main thread with a short address, or null when unresolvable. */
    fun resolve(context: Context, lat: Double, lon: Double, callback: (String?) -> Unit) {
        val key = "%.4f,%.4f".format(Locale.ROOT, lat, lon)
        cache[key]?.let { callback(it); return }
        if (!Geocoder.isPresent()) {
            callback(null)
            return
        }
        val geocoder = Geocoder(context, Locale.getDefault())
        val deliver: (Address?) -> Unit = { address ->
            val line = address?.let(::format)?.takeIf { it.isNotBlank() }
            if (line != null) cache[key] = line
            ContextCompat.getMainExecutor(context).execute { callback(line) }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocation(lat, lon, 1) { deliver(it.firstOrNull()) }
        } else {
            thread(name = "geocode") {
                @Suppress("DEPRECATION")
                val found = runCatching { geocoder.getFromLocation(lat, lon, 1) }.getOrNull()
                deliver(found?.firstOrNull())
            }
        }
    }

    /** "Via Roma 12, Sacile" — street + town beats the full multi-line address at a glance. */
    private fun format(a: Address): String {
        val street = listOfNotNull(a.thoroughfare, a.subThoroughfare)
            .joinToString(" ").ifBlank { null }
        val town = a.locality ?: a.subAdminArea
        val short = listOfNotNull(street, town).joinToString(", ")
        return short.ifBlank { a.getAddressLine(0) ?: "" }
    }
}
