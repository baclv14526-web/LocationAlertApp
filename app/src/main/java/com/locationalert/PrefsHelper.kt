package com.locationalert

import android.content.Context

object PrefsHelper {

    private const val PREFS = "location_alert_prefs"
    private const val KEY_LAT = "target_lat"
    private const val KEY_LON = "target_lon"
    private const val KEY_NAME = "target_name"
    private const val KEY_RADIUS = "alert_radius"

    fun saveTarget(context: Context, lat: Double, lon: Double, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAT, lat.toBits())
            .putLong(KEY_LON, lon.toBits())
            .putString(KEY_NAME, name)
            .apply()
    }

    fun loadTarget(context: Context): Triple<Double, Double, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = Double.fromBits(prefs.getLong(KEY_LAT, 0L))
        val lon = Double.fromBits(prefs.getLong(KEY_LON, 0L))
        val name = prefs.getString(KEY_NAME, "") ?: ""
        return Triple(lat, lon, name)
    }

    fun saveRadius(context: Context, radius: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_RADIUS, radius)
            .apply()
    }

    fun loadRadius(context: Context): Float {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_RADIUS, LocationTrackingService.DEFAULT_RADIUS)
    }
}
