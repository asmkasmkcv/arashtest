package com.example.familytracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class LocationForegroundService : Service() {

    private var locationManager: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var email: String = ""
    private var password: String = ""
    private var apiUrl: String = ""
    private var lastLocation: Location? = null

    companion object {
        const val CHANNEL_ID = "tracker_fg_channel"
        const val NOTIF_ID = 1001
        const val EXTRA_EMAIL = "email"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_API_URL = "api_url"

        fun startService(context: Context, email: String, password: String, apiUrl: String) {
            val intent = Intent(context, LocationForegroundService::class.java).apply {
                putExtra(EXTRA_EMAIL, email)
                putExtra(EXTRA_PASSWORD, password)
                putExtra(EXTRA_API_URL, apiUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, LocationForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            sendLocationToServer(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val loc = lastLocation ?: getLastKnownLocationSafe()
            if (loc != null) {
                sendLocationToServer(loc)
            } else {
                requestSingleUpdateSafe()
            }
            handler.postDelayed(this, 6000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("ارسال موقعیت در پس‌زمینه فعال است"))
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            email = it.getStringExtra(EXTRA_EMAIL) ?: email
            password = it.getStringExtra(EXTRA_PASSWORD) ?: password
            apiUrl = it.getStringExtra(EXTRA_API_URL) ?: apiUrl
        }

        startLocationUpdatesSafe()
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed(heartbeatRunnable, 4000L)

        return START_STICKY
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun getLastKnownLocationSafe(): Location? {
        if (!hasLocationPermission()) return null
        val lm = locationManager ?: return null
        return try {
            val gpsLoc = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null
            val netLoc = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null
            when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time >= netLoc.time) gpsLoc else netLoc
                else -> gpsLoc ?: netLoc
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun requestSingleUpdateSafe() {
        if (!hasLocationPermission()) return
        val lm = locationManager ?: return
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {}
    }

    private fun startLocationUpdatesSafe() {
        if (!hasLocationPermission()) return
        val lm = locationManager ?: return
        try {
            lm.removeUpdates(locationListener)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 2f, locationListener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 2f, locationListener, Looper.getMainLooper())
            }
            getLastKnownLocationSafe()?.let {
                lastLocation = it
                sendLocationToServer(it)
            }
        } catch (_: SecurityException) {}
    }

    private fun sendLocationToServer(loc: Location) {
        if (email.isBlank() || password.isBlank() || apiUrl.isBlank()) return
        val lat = loc.latitude
        val lng = loc.longitude
        val accuracy = loc.accuracy
        val speed = if (loc.hasSpeed()) loc.speed else 0f

        executor.execute {
            try {
                val url = URL(apiUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                val body = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                    put("lat", lat)
                    put("lng", lng)
                    put("accuracy", accuracy)
                    put("speed", speed)
                    put("source", "android_fg_service")
                }.toString()

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }

                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ردیاب موقعیت زنده")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "سرویس ارسال موقعیت پس‌زمینه",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeatRunnable)
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {}
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
