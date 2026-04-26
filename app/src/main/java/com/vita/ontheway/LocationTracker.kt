package com.vita.ontheway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

object LocationTracker {
    private const val TAG = "OTW_LOCATION"
    private const val INTERVAL_MS = 5000L   // v0 fixed 5s
    private const val MIN_DISTANCE_M = 0f   // always collect

    private var locationManager: LocationManager? = null
    private var listener: LocationListener? = null
    private var isTracking = false
    private var appContext: Context? = null

    fun startTracking(ctx: Context) {
        if (isTracking) {
            Log.d(TAG, "already tracking")
            return
        }

        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no location permission")
            return
        }

        appContext = ctx.applicationContext
        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val newListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocation(location)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            val lm = locationManager!!
            Handler(Looper.getMainLooper()).post {
                try {
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        lm.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, INTERVAL_MS, MIN_DISTANCE_M, newListener
                        )
                    }
                    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, INTERVAL_MS, MIN_DISTANCE_M, newListener
                        )
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "requestLocationUpdates failed", e)
                }
            }
            listener = newListener
            isTracking = true
            Log.d(TAG, "GPS tracking started (5s interval)")
        } catch (e: Exception) {
            Log.e(TAG, "startTracking failed", e)
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        listener?.let { locationManager?.removeUpdates(it) }
        listener = null
        isTracking = false
        Log.d(TAG, "GPS tracking stopped")
    }

    fun isActive(): Boolean = isTracking

    private fun handleLocation(location: Location) {
        val ctx = appContext ?: return
        val trace = LocationTrace(
            ts = System.currentTimeMillis(),
            lat = location.latitude,
            lng = location.longitude,
            speed = if (location.hasSpeed()) location.speed else 0f,
            accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        )

        try {
            CallLogDb.get(ctx).insertLocationTrace(trace)
            Log.d(TAG, "saved: ${trace.lat},${trace.lng} spd=${trace.speed}m/s acc=${trace.accuracy}m")
        } catch (e: Exception) {
            Log.e(TAG, "insert failed", e)
        }
    }
}
