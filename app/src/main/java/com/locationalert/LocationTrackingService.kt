package com.locationalert

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*

class LocationTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "location_tracking_channel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "LocationTrackingService"

        // Default alert radius 20–30m (we use 25m as center)
        const val DEFAULT_RADIUS = 25f
        const val MIN_RADIUS = 20f
        const val MAX_RADIUS = 30f
    }

    // ── Binder ───────────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): LocationTrackingService = this@LocationTrackingService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ── State ────────────────────────────────────────────────────────────────
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var targetLat: Double = 0.0
    private var targetLon: Double = 0.0
    private var hasTarget = false
    private var isTracking = false
    private var alertRadius = DEFAULT_RADIUS
    private var mp3Uri: Uri? = null
    private var alertTriggered = false          // prevent spam
    private var lastAlertTime = 0L
    private val alertCooldownMs = 15_000L       // 15 seconds cooldown

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var callback: TrackingCallback? = null

    // ── Callback Interface ────────────────────────────────────────────────────
    interface TrackingCallback {
        fun onLocationUpdate(lat: Double, lon: Double, accuracy: Float, provider: String)
        fun onDistanceUpdate(distance: Float)
        fun onArrivalAlert(distance: Float)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Sẵn sàng theo dõi..."))
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // ── Public API ───────────────────────────────────────────────────────────
    fun setCallback(cb: TrackingCallback) { callback = cb }

    fun setTarget(lat: Double, lon: Double) {
        targetLat = lat
        targetLon = lon
        hasTarget = true
        alertTriggered = false
        PrefsHelper.saveTarget(this, lat, lon, "")
    }

    fun clearTarget() {
        hasTarget = false
        targetLat = 0.0
        targetLon = 0.0
        alertTriggered = false
    }

    fun setMp3Uri(uri: Uri?) {
        mp3Uri = uri
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun setAlertRadius(radius: Float) {
        alertRadius = radius.coerceIn(MIN_RADIUS, MAX_RADIUS)
        PrefsHelper.saveRadius(this, alertRadius)
    }

    fun hasTarget() = hasTarget
    fun isTracking() = isTracking

    // ── Tracking Control ─────────────────────────────────────────────────────
    fun startTracking() {
        if (isTracking) return
        isTracking = true
        alertTriggered = false
        setupLocationUpdates()
        updateNotification("📡 Đang theo dõi vị trí...")
    }

    fun stopTracking() {
        isTracking = false
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        locationCallback = null
        stopAlertSound()
        updateNotification("⏹ Đã dừng theo dõi")
    }

    // ── Location Updates ─────────────────────────────────────────────────────
    private fun setupLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(1_500L)
            .setMinUpdateDistanceMeters(1f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                handleLocationUpdate(location)
            }
        }

        try {
            fusedLocationClient?.requestLocationUpdates(
                request,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied: ${e.message}")
            stopTracking()
        }
    }

    private fun handleLocationUpdate(location: Location) {
        val providerName = when (location.provider) {
            "gps" -> "GPS"
            "network" -> "WiFi/Mạng"
            "fused" -> "Tổng hợp"
            else -> location.provider ?: "Không rõ"
        }

        callback?.onLocationUpdate(
            location.latitude,
            location.longitude,
            location.accuracy,
            providerName
        )

        if (!hasTarget) return

        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude,
            targetLat, targetLon,
            results
        )
        val distance = results[0]

        callback?.onDistanceUpdate(distance)
        updateNotification(buildNotificationText(distance))

        // Check alert threshold (MIN_RADIUS ≤ distance ≤ MAX_RADIUS, or closer)
        if (distance <= alertRadius) {
            val now = System.currentTimeMillis()
            if (!alertTriggered || now - lastAlertTime > alertCooldownMs) {
                triggerAlert(distance)
                lastAlertTime = now
                alertTriggered = true
                callback?.onArrivalAlert(distance)
            }
        } else if (distance > alertRadius + 10f) {
            // Reset alert when user moves away enough
            alertTriggered = false
        }
    }

    // ── Alert ────────────────────────────────────────────────────────────────
    private fun triggerAlert(distance: Float) {
        // 1. Vibrate
        val pattern = longArrayOf(0, 500, 200, 500, 200, 800, 200, 800)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, -1)
            vibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }

        // 2. Play sound
        playSoundAlert()

        // 3. System notification with high priority
        showAlertNotification(distance)
    }

    private fun playSoundAlert() {
        try {
            stopAlertSound()
            mediaPlayer = MediaPlayer()
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            mediaPlayer!!.setAudioAttributes(audioAttr)

            if (mp3Uri != null) {
                mediaPlayer!!.setDataSource(applicationContext, mp3Uri!!)
            } else {
                // Default alarm sound
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer!!.setDataSource(applicationContext, uri)
            }

            mediaPlayer!!.prepare()
            mediaPlayer!!.isLooping = false
            mediaPlayer!!.start()

            // Auto-stop after 10 seconds
            Handler(Looper.getMainLooper()).postDelayed({ stopAlertSound() }, 10_000L)

        } catch (e: Exception) {
            Log.e(TAG, "Error playing sound: ${e.message}")
            // Fallback: use Ringtone
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(applicationContext, uri)
                ringtone?.play()
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback ringtone error: ${ex.message}")
            }
        }
    }

    private fun stopAlertSound() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        } catch (e: Exception) { /* ignore */ }
        mediaPlayer = null
    }

    private fun showAlertNotification(distance: Float) {
        val alertChannel = "alert_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                alertChannel, "Cảnh báo đến nơi",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }

        val notification = NotificationCompat.Builder(this, alertChannel)
            .setSmallIcon(R.drawable.ic_location_alert)
            .setContentTitle("🔔 ĐÃ ĐẾN NƠI!")
            .setContentText("Bạn cách đích ${distance.toInt()}m")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        nm.notify(1002, notification)
    }

    // ── Notification Helpers ─────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Theo dõi vị trí",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Theo dõi vị trí GPS trong nền"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location_alert)
            .setContentTitle("Location Alert")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotificationText(distance: Float): String {
        val dist = distance.toInt()
        return when {
            dist <= 30 -> "⚠️ Gần đích! Còn ${dist}m"
            dist < 1000 -> "📍 Còn ${dist}m đến đích"
            else -> "📍 Còn ${"%.1f".format(dist / 1000f)}km đến đích"
        }
    }
}
