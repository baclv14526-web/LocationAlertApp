package com.locationalert

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.locationalert.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var trackingService: LocationTrackingService? = null
    private var isBound = false
    private var selectedMp3Uri: Uri? = null
    private var selectedMp3Name: String = ""

    // ── Permissions ─────────────────────────────────────────────────────────
    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.VIBRATE,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Snackbar.make(binding.root, "Đã cấp đầy đủ quyền", Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(
                binding.root,
                "Một số quyền bị từ chối. Ứng dụng có thể không hoạt động đúng.",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    // ── MP3 Picker ───────────────────────────────────────────────────────────
    private val mp3PickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedMp3Uri = uri
                selectedMp3Name = getFileName(uri)
                trackingService?.setMp3Uri(uri)
                updateMp3UI()
                // Persist permission across reboots
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    // ── Voice Input ──────────────────────────────────────────────────────────
    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val words = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!words.isNullOrEmpty()) {
                binding.etLocationInput.setText(words[0])
                geocodeAddress(words[0])
            }
        }
    }

    // ── Background Location ──────────────────────────────────────────────────
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Cần quyền vị trí nền để theo dõi khi khóa màn hình",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Service Connection ───────────────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val lb = binder as LocationTrackingService.LocalBinder
            trackingService = lb.getService()
            isBound = true
            trackingService?.setCallback(serviceCallback)
            // Restore saved state
            restoreServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService = null
            isBound = false
        }
    }

    // ── Service Callback ─────────────────────────────────────────────────────
    private val serviceCallback = object : LocationTrackingService.TrackingCallback {
        override fun onLocationUpdate(lat: Double, lon: Double, accuracy: Float, provider: String) {
            runOnUiThread {
                binding.tvCurrentLocation.text =
                    "📍 %.6f, %.6f\n🎯 Độ chính xác: ±${accuracy.toInt()}m | 📡 $provider"
            }
        }

        override fun onDistanceUpdate(distance: Float) {
            runOnUiThread {
                val dist = distance.toInt()
                binding.tvDistance.text = when {
                    dist < 1000 -> "Khoảng cách: ${dist}m"
                    else -> "Khoảng cách: ${"%.1f".format(dist / 1000f)}km"
                }
                // Color feedback
                val color = when {
                    dist <= 20 -> getColor(R.color.alert_red)
                    dist <= 50 -> getColor(R.color.alert_orange)
                    dist <= 200 -> getColor(R.color.alert_yellow)
                    else -> getColor(R.color.text_primary)
                }
                binding.tvDistance.setTextColor(color)
            }
        }

        override fun onArrivalAlert(distance: Float) {
            runOnUiThread {
                binding.tvStatus.text = "🔔 ĐÃ ĐẾN NƠI! Khoảng cách: ${distance.toInt()}m"
                binding.tvStatus.setTextColor(getColor(R.color.alert_red))
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()
        setupUI()
        bindTrackingService()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    // ── Setup ────────────────────────────────────────────────────────────────
    private fun setupUI() {
        // Nút ghi âm giọng nói
        binding.btnVoiceInput.setOnClickListener { startVoiceInput() }

        // Nút xóa input
        binding.btnClearInput.setOnClickListener {
            binding.etLocationInput.setText("")
            binding.tvCoordinates.text = "Chưa có tọa độ"
            trackingService?.clearTarget()
            updateTrackingUI(false)
        }

        // Nút tìm địa chỉ (geocode)
        binding.btnSearchLocation.setOnClickListener {
            val text = binding.etLocationInput.text.toString().trim()
            if (text.isNotEmpty()) {
                geocodeAddress(text)
            } else {
                Toast.makeText(this, "Vui lòng nhập địa chỉ", Toast.LENGTH_SHORT).show()
            }
        }

        // Nút nhập tọa độ thủ công
        binding.btnManualCoords.setOnClickListener { showManualCoordsDialog() }

        // Nút chọn MP3
        binding.btnSelectMp3.setOnClickListener { pickMp3File() }

        // Nút xóa MP3
        binding.btnRemoveMp3.setOnClickListener {
            selectedMp3Uri = null
            selectedMp3Name = ""
            trackingService?.setMp3Uri(null)
            updateMp3UI()
        }

        // Nút bắt đầu / dừng theo dõi
        binding.btnStartTracking.setOnClickListener { startTracking() }
        binding.btnStopTracking.setOnClickListener { stopTracking() }

        // Bán kính cảnh báo slider
        binding.sliderRadius.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvRadiusValue.text = "${value.toInt()}m"
                trackingService?.setAlertRadius(value)
            }
        }

        updateTrackingUI(false)
        updateMp3UI()
    }

    private fun bindTrackingService() {
        val intent = Intent(this, LocationTrackingService::class.java)
        startService(intent) // ensure it's running
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ── Permissions ──────────────────────────────────────────────────────────
    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
        // Background location requires separate request on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                AlertDialog.Builder(this)
                    .setTitle("Quyền vị trí nền")
                    .setMessage("Để theo dõi vị trí khi màn hình tắt, hãy cho phép \"Luôn luôn\" trong cài đặt quyền vị trí.")
                    .setPositiveButton("Cài đặt") { _, _ ->
                        backgroundLocationLauncher.launch(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        )
                    }
                    .setNegativeButton("Bỏ qua", null)
                    .show()
            }
        }
    }

    // ── Voice Input ──────────────────────────────────────────────────────────
    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên địa điểm hoặc địa chỉ...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Thiết bị không hỗ trợ nhận dạng giọng nói", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Geocoding (via Nominatim OSM - free, no API key) ────────────────────
    private fun geocodeAddress(address: String) {
        binding.tvStatus.text = "🔍 Đang tìm địa chỉ..."
        Thread {
            try {
                val encoded = Uri.encode(address)
                val url = java.net.URL(
                    "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1&accept-language=vi"
                )
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "LocationAlertApp/1.0")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val response = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONArray(response)

                runOnUiThread {
                    if (json.length() > 0) {
                        val place = json.getJSONObject(0)
                        val lat = place.getString("lat").toDouble()
                        val lon = place.getString("lon").toDouble()
                        val displayName = place.getString("display_name")
                        setTargetLocation(lat, lon, displayName)
                    } else {
                        binding.tvStatus.text = "❌ Không tìm thấy địa chỉ. Thử nhập tọa độ thủ công."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvStatus.text = "❌ Lỗi kết nối: ${e.message}"
                }
            }
        }.start()
    }

    private fun setTargetLocation(lat: Double, lon: Double, name: String) {
        binding.tvCoordinates.text = "🎯 %.6f, %.6f".format(lat, lon)
        binding.tvStatus.text = "✅ Đã đặt: $name"
        trackingService?.setTarget(lat, lon)
        // Save for restoration
        PrefsHelper.saveTarget(this, lat, lon, name)
    }

    // ── Manual Coordinates Dialog ─────────────────────────────────────────────
    private fun showManualCoordsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_coords, null)
        val etLat = dialogView.findViewById<android.widget.EditText>(R.id.et_lat)
        val etLon = dialogView.findViewById<android.widget.EditText>(R.id.et_lon)

        AlertDialog.Builder(this)
            .setTitle("Nhập tọa độ thủ công")
            .setView(dialogView)
            .setPositiveButton("Xác nhận") { _, _ ->
                val latStr = etLat.text.toString().trim()
                val lonStr = etLon.text.toString().trim()
                try {
                    val lat = latStr.toDouble()
                    val lon = lonStr.toDouble()
                    if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                        setTargetLocation(lat, lon, "Tọa độ: $latStr, $lonStr")
                    } else {
                        Toast.makeText(this, "Tọa độ không hợp lệ", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Vui lòng nhập số hợp lệ", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ── MP3 ──────────────────────────────────────────────────────────────────
    private fun pickMp3File() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
        }
        mp3PickerLauncher.launch(intent)
    }

    private fun getFileName(uri: Uri): String {
        var name = "file.mp3"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) {
                name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun updateMp3UI() {
        if (selectedMp3Uri != null) {
            binding.tvMp3Name.text = "🎵 $selectedMp3Name"
            binding.btnRemoveMp3.visibility = android.view.View.VISIBLE
            binding.btnSelectMp3.text = "Đổi file MP3"
        } else {
            binding.tvMp3Name.text = "Chưa chọn file (dùng âm thanh mặc định)"
            binding.btnRemoveMp3.visibility = android.view.View.GONE
            binding.btnSelectMp3.text = "Chọn file MP3"
        }
    }

    // ── Tracking ─────────────────────────────────────────────────────────────
    private fun startTracking() {
        if (!isBound || trackingService == null) {
            Toast.makeText(this, "Dịch vụ chưa sẵn sàng", Toast.LENGTH_SHORT).show()
            return
        }
        if (!trackingService!!.hasTarget()) {
            Toast.makeText(this, "Vui lòng đặt vị trí đích trước", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasLocationPermission()) {
            checkAndRequestPermissions()
            return
        }
        trackingService!!.startTracking()
        updateTrackingUI(true)
    }

    private fun stopTracking() {
        trackingService?.stopTracking()
        updateTrackingUI(false)
        binding.tvStatus.text = "⏹ Đã dừng theo dõi"
        binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
        binding.tvDistance.text = "Khoảng cách: --"
        binding.tvDistance.setTextColor(getColor(R.color.text_primary))
    }

    private fun updateTrackingUI(isTracking: Boolean) {
        binding.btnStartTracking.isEnabled = !isTracking
        binding.btnStopTracking.isEnabled = isTracking
        binding.btnStartTracking.alpha = if (isTracking) 0.5f else 1f
        binding.btnStopTracking.alpha = if (!isTracking) 0.5f else 1f

        if (isTracking) {
            binding.tvStatus.text = "📡 Đang theo dõi..."
            binding.tvStatus.setTextColor(getColor(R.color.accent_green))
            binding.indicatorTracking.visibility = android.view.View.VISIBLE
        } else {
            binding.indicatorTracking.visibility = android.view.View.GONE
        }
    }

    private fun restoreServiceState() {
        val (lat, lon, name) = PrefsHelper.loadTarget(this)
        if (lat != 0.0 && lon != 0.0) {
            binding.tvCoordinates.text = "🎯 %.6f, %.6f".format(lat, lon)
            binding.tvStatus.text = "✅ Đích: $name"
            trackingService?.setTarget(lat, lon)
        }
        val radius = PrefsHelper.loadRadius(this)
        binding.sliderRadius.value = radius
        binding.tvRadiusValue.text = "${radius.toInt()}m"
        trackingService?.setAlertRadius(radius)

        if (trackingService?.isTracking() == true) {
            updateTrackingUI(true)
        }
    }

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
}
