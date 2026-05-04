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
import java.util.concurrent.Executors

object LocationTracker {
    private const val TAG = "OTW_LOCATION"

    // [P1] DB INSERT를 메인 스레드에서 분리
    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LocationTracker-DB").apply { isDaemon = true }
    }

    // speed thresholds (m/s)
    private const val SPEED_STOPPED = 0.5f      // < 0.5 = stopped
    private const val SPEED_SLOW = 5.0f         // < 5.0 = slow (~18 km/h)

    // collection intervals (ms)
    private const val INTERVAL_DRIVING = 5000L  // >= 18 km/h
    private const val INTERVAL_SLOW = 10000L    // slow / congestion
    private const val INTERVAL_STOPPED = 30000L // stopped (minimal)

    // auto-IDLE after 3 min stopped
    private const val IDLE_THRESHOLD_MS = 180_000L

    private const val MIN_DISTANCE_M = 0f

    private var locationManager: LocationManager? = null
    private var listener: LocationListener? = null
    private var isTracking = false
    private var appContext: Context? = null

    private var currentInterval = INTERVAL_DRIVING
    private var lastMovingTime: Long = 0L

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

        currentInterval = INTERVAL_DRIVING
        lastMovingTime = System.currentTimeMillis()

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
                            LocationManager.GPS_PROVIDER, currentInterval, MIN_DISTANCE_M, newListener
                        )
                    }
                    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, currentInterval, MIN_DISTANCE_M, newListener
                        )
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "requestLocationUpdates failed", e)
                }
            }
            listener = newListener
            isTracking = true
            Log.d(TAG, "GPS tracking started (${currentInterval}ms interval)")
            OtwFileLogger.log(TAG, "GPS tracking started — requestLocationUpdates 호출됨")
        } catch (e: Exception) {
            Log.e(TAG, "startTracking failed", e)
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        listener?.let { locationManager?.removeUpdates(it) }
        listener = null
        isTracking = false
        lastMovingTime = 0L
        currentInterval = INTERVAL_DRIVING
        Log.d(TAG, "GPS tracking stopped")
        OtwFileLogger.log(TAG, "GPS tracking stopped — removeUpdates 호출됨")
    }

    fun isActive(): Boolean = isTracking
    fun getCurrentIntervalMs(): Long = currentInterval

    private fun handleLocation(location: Location) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        val speed = if (location.hasSpeed()) location.speed else 0f

        // 1. save trace
        val trace = LocationTrace(
            ts = now,
            lat = location.latitude,
            lng = location.longitude,
            speed = speed,
            accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        )

        // [P1] DB INSERT를 백그라운드로 분리 (GPS 콜백은 메인 스레드)
        dbExecutor.execute {
            try {
                CallLogDb.get(ctx).insertLocationTrace(trace)
            } catch (e: Exception) {
                Log.e(TAG, "insert failed", e)
            }
        }

        // 2. speed tier
        val targetInterval = when {
            speed >= SPEED_SLOW -> INTERVAL_DRIVING
            speed >= SPEED_STOPPED -> INTERVAL_SLOW
            else -> INTERVAL_STOPPED
        }

        val speedLabel = when {
            speed >= SPEED_SLOW -> "DRIVING"
            speed >= SPEED_STOPPED -> "SLOW"
            else -> "STOPPED"
        }

        val speedKmh = if (speed > 0) speed * 3.6f else 0f
        Log.d(TAG, "${trace.lat},${trace.lng} spd=${"%.1f".format(speed)}m/s [$speedLabel] interval=${targetInterval}ms")
        OtwFileLogger.log("LocationTrace", "lat=${"%.6f".format(trace.lat)} lng=${"%.6f".format(trace.lng)} speed=${"%.0f".format(speedKmh)}km/h")

        // 3. update lastMovingTime
        if (speed >= SPEED_STOPPED) {
            lastMovingTime = now
        }

        // 4. auto-IDLE after 3 min stopped
        if (lastMovingTime > 0L && now - lastMovingTime > IDLE_THRESHOLD_MS) {
            Log.d(TAG, "3min stopped -> auto IDLE")
            DrivingModeManager.setMode(ctx, DrivingMode.IDLE)
            return // setMode calls stopTracking
        }

        // 5. change interval if needed
        if (targetInterval != currentInterval) {
            Log.d(TAG, "interval change: ${currentInterval}ms -> ${targetInterval}ms")
            currentInterval = targetInterval
            restartLocationUpdates()
        }
    }

    private fun restartLocationUpdates() {
        if (!isTracking) return
        val currentListener = listener ?: return
        val lm = locationManager ?: return

        try {
            lm.removeUpdates(currentListener)
            Handler(Looper.getMainLooper()).post {
                try {
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        lm.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, currentInterval, MIN_DISTANCE_M, currentListener
                        )
                    }
                    if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        lm.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, currentInterval, MIN_DISTANCE_M, currentListener
                        )
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "restart failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "restart error", e)
        }
    }
}
