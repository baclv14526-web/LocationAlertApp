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
        const val ALERT_CHANNEL_ID = "alert_channel"
        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002
        const val TAG = "LocationTrackingService"

        // Default alert radius 20–300m
        const val DEFAULT_RADIUS = 50f
        const val MIN_RADIUS = 20f
        const val MAX_RADIUS = 300f
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
    fun setCallback(cb: TrackingCallback?) { callback = cb }

    fun setTarget(lat: Double, lon: Double) {
        targetLat = lat
        targetLon = lon
        hasTarget = true
        alertTriggered = false
        // Không lưu Prefs ở đây — MainActivity chịu trách nhiệm lưu (có tên đầy đủ).
        // Trước đây gọi saveTarget(..., "") ở đây đã xóa mất tên đã lưu mỗi khi
        // restoreServiceState() gọi lại setTarget() lúc khởi động app.
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
    fun getTarget(): Pair<Double, Double>? = if (hasTarget) Pair(targetLat, targetLon) else null
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
        } else if (distance > alertRadius + hysteresisBuffer()) {
            // Reset alert khi đã ra xa đủ (tránh dao động qua lại ở biên radius)
            alertTriggered = false
        }
    }

    /**
     * Buffer chống dao động (hysteresis) khi reset trạng thái alert.
     * Với radius nhỏ (20-30m) dùng buffer cố định 10m như cũ.
     * Với radius lớn (tới 300m) buffer tỉ lệ 20% để tránh cảnh báo
     * lặp lại liên tục khi xe di chuyển quanh biên radius.
     */
    private fun hysteresisBuffer(): Float = maxOf(10f, alertRadius * 0.2f)

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

            // prepare() là lệnh BLOCKING — có thể gây ANR nếu chạy trên main thread
            // (handleLocationUpdate chạy trên Looper.getMainLooper()).
            // Dùng prepareAsync() + listener để không chặn main thread.
            mediaPlayer!!.setOnPreparedListener { mp ->
                mp.isLooping = false
                mp.start()
                // Auto-stop sau 10 giây
                Handler(Looper.getMainLooper()).postDelayed({ stopAlertSound() }, 10_000L)
            }
            mediaPlayer!!.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                stopAlertSound()
                true
            }
            mediaPlayer!!.prepareAsync()

        } catch (e: Exception) {
            Log.e(TAG, "Error playing sound: ${e.message}")
            // Fallback: dùng Ringtone (không cần prepare, an toàn hơn)
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
        // Channel được tạo 1 lần duy nhất trong createNotificationChannel() (onCreate),
        // không cần tạo lại mỗi lần alert — tránh gọi createNotificationChannel() thừa
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location_alert)
            .setContentTitle("🔔 ĐÃ ĐẾN NƠI!")
            .setContentText("Bạn cách đích ${distance.toInt()}m")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }

    // ── Notification Helpers ─────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel 1: thông báo theo dõi liên tục (foreground service)
            val trackingChannel = NotificationChannel(
                CHANNEL_ID, "Theo dõi vị trí",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Theo dõi vị trí GPS trong nền"
                setShowBadge(false)
            }

            // Channel 2: cảnh báo khi đến nơi (ưu tiên cao, tạo 1 lần duy nhất)
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID, "Cảnh báo đến nơi",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            nm.createNotificationChannel(trackingChannel)
            nm.createNotificationChannel(alertChannel)
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
