package com.teya.agent.household

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Reads the device's last-known location and reverse-geocodes it to a city name, for the "is this
 * home?" onboarding/Admin step. Confirm-don't-type: we never ask the user to type a city.
 */
object LocationProbe {
    data class Home(val city: String, val coords: String)

    suspend fun detect(context: Context): Home = withContext(Dispatchers.IO) {
        val loc = lastKnown(context)
            ?: return@withContext Home("Location unavailable", "Enable location to detect home")
        val coords = "%.4f°, %.4f°".format(loc.latitude, loc.longitude)
        Home(reverseGeocode(context, loc) ?: "Detected location", coords)
    }

    private fun lastKnown(context: Context): Location? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        Log.e("LocationProbe", "Location read failed", e)
        null
    }

    private fun reverseGeocode(context: Context, loc: Location): String? = try {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
            ?.firstOrNull()?.let { a ->
                listOfNotNull(a.locality ?: a.subAdminArea, a.countryName).joinToString(", ").ifBlank { null }
            }
    } catch (e: Exception) {
        Log.w("LocationProbe", "Reverse geocode failed", e)
        null
    }
}
