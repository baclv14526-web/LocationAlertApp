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
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.locationalert.databinding.ActivityMainBinding
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var trackingService: LocationTrackingService? = null
    private var isBound = false
    private var selectedMp3Uri: Uri? = null
    private var selectedMp3Name: String = ""

    // Vị trí hiện tại (cập nhật realtime từ service)
    private var currentLat = 0.0
    private var currentLon = 0.0

    // ── Permissions ──────────────────────────────────────────────────────────
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
        if (!allGranted) Snackbar.make(
            binding.root,
            "Một số quyền bị từ chối. Ứng dụng có thể không hoạt động đúng.",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private val mp3PickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedMp3Uri  = uri
                selectedMp3Name = getFileName(uri)
                trackingService?.setMp3Uri(uri)
                updateMp3UI()
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!words.isNullOrEmpty()) {
                binding.etLocationInput.setText(words[0])
                geocodeAddress(words[0])
            }
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(
            this, "Cần quyền vị trí nền để theo dõi khi khóa màn hình", Toast.LENGTH_LONG
        ).show()
    }

    // ── Service Connection ────────────────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val lb = binder as LocationTrackingService.LocalBinder
            trackingService = lb.getService()
            isBound = true
            trackingService?.setCallback(serviceCallback)
            restoreServiceState()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService = null
            isBound = false
        }
    }

    // ── Service Callback ──────────────────────────────────────────────────────
    private val serviceCallback = object : LocationTrackingService.TrackingCallback {
        override fun onLocationUpdate(lat: Double, lon: Double, accuracy: Float, provider: String) {
            // Lưu vị trí hiện tại để dùng khi fetch route
            currentLat = lat
            currentLon = lon
            runOnUiThread {
                binding.tvCurrentLocation.text =
                    "📍 %.6f, %.6f\n🎯 Độ chính xác: ±${accuracy.toInt()}m | 📡 $provider".format(lat, lon)
                // Hiện nút route khi đã có cả vị trí hiện tại lẫn đích
                updateRouteButtonVisibility()
            }
        }

        override fun onDistanceUpdate(distance: Float) {
            runOnUiThread {
                val dist = distance.toInt()
                binding.tvDistance.text = when {
                    dist < 1000 -> "Khoảng cách: ${dist}m"
                    else        -> "Khoảng cách: ${"%.1f".format(dist / 1000f)}km"
                }
                binding.tvDistance.setTextColor(when {
                    dist <= 20  -> getColor(R.color.alert_red)
                    dist <= 50  -> getColor(R.color.alert_orange)
                    dist <= 200 -> getColor(R.color.alert_yellow)
                    else        -> getColor(R.color.text_primary)
                })
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
        if (isBound) { unbindService(serviceConnection); isBound = false }
    }

    // ── Setup UI ──────────────────────────────────────────────────────────────
    private fun setupUI() {
        binding.btnVoiceInput.setOnClickListener    { startVoiceInput() }
        binding.btnSearchLocation.setOnClickListener {
            val text = binding.etLocationInput.text.toString().trim()
            if (text.isNotEmpty()) geocodeAddress(text)
            else Toast.makeText(this, "Vui lòng nhập địa chỉ", Toast.LENGTH_SHORT).show()
        }
        binding.btnClearInput.setOnClickListener {
            binding.etLocationInput.setText("")
            binding.tvCoordinates.text = "Chưa có tọa độ"
            binding.tvStatus.text = "Chưa đặt vị trí đích"
            binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
            trackingService?.clearTarget()
            updateTrackingUI(false)
            updateRouteButtonVisibility()
        }
        binding.btnManualCoords.setOnClickListener  { showManualCoordsDialog() }
        binding.btnSelectMp3.setOnClickListener     { pickMp3File() }
        binding.btnRemoveMp3.setOnClickListener {
            selectedMp3Uri  = null
            selectedMp3Name = ""
            trackingService?.setMp3Uri(null)
            updateMp3UI()
        }
        binding.btnStartTracking.setOnClickListener { startTracking() }
        binding.btnStopTracking.setOnClickListener  { stopTracking() }

        // ── NÚT DANH SÁCH ĐƯỜNG ─────────────────────────────────────────────
        binding.btnShowRoute.setOnClickListener     { showRouteDialog() }

        binding.sliderRadius.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvRadiusValue.text = "${value.toInt()}m"
                trackingService?.setAlertRadius(value)
            }
        }

        updateTrackingUI(false)
        updateMp3UI()
        updateRouteButtonVisibility()
    }

    /** Nút "Danh sách đường" chỉ hiện khi đã có GPS + đích */
    private fun updateRouteButtonVisibility() {
        val hasGps    = currentLat != 0.0 && currentLon != 0.0
        val hasTarget = trackingService?.hasTarget() ?: false
        binding.btnShowRoute.visibility = if (hasGps && hasTarget) View.VISIBLE else View.GONE
    }

    private fun bindTrackingService() {
        val intent = Intent(this, LocationTrackingService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ── Permissions ───────────────────────────────────────────────────────────
    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this)
                .setTitle("Quyền vị trí nền")
                .setMessage("Để theo dõi vị trí khi màn hình tắt, hãy cho phép \"Luôn luôn\" trong cài đặt quyền vị trí.")
                .setPositiveButton("Cài đặt") { _, _ ->
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton("Bỏ qua", null)
                .show()
        }
    }

    // ── Voice Input ───────────────────────────────────────────────────────────
    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên địa điểm hoặc địa chỉ...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { voiceLauncher.launch(intent) }
        catch (e: Exception) {
            Toast.makeText(this, "Thiết bị không hỗ trợ nhận dạng giọng nói", Toast.LENGTH_SHORT).show()
        }
    }

    // ── SSL helper (fix Android 9 TLS handshake) ────────────────────────────────
    private fun buildSslContext(): SSLContext {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return SSLContext.getInstance("TLSv1.2").also {
            it.init(null, tmf.trustManagers, null)
        }
    }

    // ── Geocoding ─────────────────────────────────────────────────────────────
    private fun geocodeAddress(address: String) {
        binding.tvStatus.text = "🔍 Đang tìm địa chỉ..."
        Thread {
            try {
                val encoded = Uri.encode(address)
                val url  = java.net.URL(
                    "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1&accept-language=vi"
                )
                val conn = (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = buildSslContext().socketFactory
                    setRequestProperty("User-Agent", "LocationAlertApp/1.0 Android")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 20_000; readTimeout = 20_000
                    instanceFollowRedirects = true
                }
                val json = org.json.JSONArray(conn.inputStream.bufferedReader().readText())
                runOnUiThread {
                    if (json.length() > 0) {
                        val place = json.getJSONObject(0)
                        setTargetLocation(
                            place.getString("lat").toDouble(),
                            place.getString("lon").toDouble(),
                            place.getString("display_name")
                        )
                    } else {
                        binding.tvStatus.text = "❌ Không tìm thấy địa chỉ. Thử nhập tọa độ thủ công."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { binding.tvStatus.text = "❌ Lỗi kết nối: ${e.message}" }
            }
        }.start()
    }

    private fun setTargetLocation(lat: Double, lon: Double, name: String) {
        binding.tvCoordinates.text = "🎯 %.6f, %.6f".format(lat, lon)
        binding.tvStatus.text      = "✅ Đã đặt: ${name.take(60)}${if (name.length > 60) "..." else ""}"
        binding.tvStatus.setTextColor(getColor(R.color.accent_green))
        trackingService?.setTarget(lat, lon)
        PrefsHelper.saveTarget(this, lat, lon, name)
        updateRouteButtonVisibility()
    }

    // ── Manual Coords Dialog ──────────────────────────────────────────────────
    private fun showManualCoordsDialog() {
        val v   = layoutInflater.inflate(R.layout.dialog_manual_coords, null)
        val etLat = v.findViewById<android.widget.EditText>(R.id.et_lat)
        val etLon = v.findViewById<android.widget.EditText>(R.id.et_lon)
        AlertDialog.Builder(this)
            .setTitle("Nhập tọa độ thủ công")
            .setView(v)
            .setPositiveButton("Xác nhận") { _, _ ->
                try {
                    val lat = etLat.text.toString().trim().toDouble()
                    val lon = etLon.text.toString().trim().toDouble()
                    if (lat in -90.0..90.0 && lon in -180.0..180.0)
                        setTargetLocation(lat, lon, "Tọa độ: $lat, $lon")
                    else
                        Toast.makeText(this, "Tọa độ không hợp lệ", Toast.LENGTH_SHORT).show()
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Vui lòng nhập số hợp lệ", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null).show()
    }

    // ── Route Dialog ──────────────────────────────────────────────────────────
    private fun showRouteDialog() {
        val target = trackingService?.getTarget()
        if (target == null || currentLat == 0.0) {
            Toast.makeText(this, "Cần có GPS và vị trí đích", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_route, null)
        val tvSummaryDist  = dialogView.findViewById<TextView>(R.id.tvRouteSummaryDist)
        val tvSummaryTime  = dialogView.findViewById<TextView>(R.id.tvRouteSummaryTime)
        val tvStepCount    = dialogView.findViewById<TextView>(R.id.tvRouteStepCount)
        val layoutLoading  = dialogView.findViewById<View>(R.id.layoutRouteLoading)
        val tvError        = dialogView.findViewById<TextView>(R.id.tvRouteError)
        val scrollSteps    = dialogView.findViewById<View>(R.id.scrollRouteSteps)
        val layoutSteps    = dialogView.findViewById<LinearLayout>(R.id.layoutRouteSteps)

        val dialog = AlertDialog.Builder(this, R.style.RouteDialogTheme)
            .setTitle("🗺️ Danh sách đường đi")
            .setView(dialogView)
            .setPositiveButton("Đóng", null)
            .create()
        dialog.show()

        // Fetch route trên background thread
        Thread {
            val result = RouteHelper.fetchRoute(
                currentLat, currentLon,
                target.first, target.second
            )
            runOnUiThread {
                layoutLoading.visibility = View.GONE

                if (result.error != null) {
                    tvError.text       = "❌ ${result.error}"
                    tvError.visibility = View.VISIBLE
                    return@runOnUiThread
                }

                // Cập nhật tổng quan
                tvSummaryDist.text = RouteHelper.formatDistance(result.totalDistanceM)
                tvSummaryTime.text = RouteHelper.formatDuration(result.totalDurationSec)

                // Lọc bước có ý nghĩa (bỏ bước 0m không phải arrive/depart)
                val meaningfulSteps = result.steps.filter { step ->
                    step.distanceM > 0 || step.maneuver == "arrive" || step.maneuver == "depart"
                }
                tvStepCount.text = "${meaningfulSteps.size} bước"

                if (meaningfulSteps.isEmpty()) {
                    tvError.text       = "Không có dữ liệu đường đi"
                    tvError.visibility = View.VISIBLE
                    return@runOnUiThread
                }

                // Render từng bước
                layoutSteps.removeAllViews()
                val inflater = LayoutInflater.from(this)
                meaningfulSteps.forEachIndexed { index, step ->
                    val itemView = inflater.inflate(R.layout.item_route_step, layoutSteps, false)

                    val tvIcon        = itemView.findViewById<TextView>(R.id.tvStepIcon)
                    val tvStreet      = itemView.findViewById<TextView>(R.id.tvStepStreet)
                    val tvInstruction = itemView.findViewById<TextView>(R.id.tvStepInstruction)
                    val tvDistance    = itemView.findViewById<TextView>(R.id.tvStepDistance)
                    val viewLine      = itemView.findViewById<View>(R.id.viewStepLine)

                    // Icon + màu nền theo loại bước
                    tvIcon.text = RouteHelper.maneuverIcon(step.maneuver,
                        extractModifier(step.instruction))
                    when (step.maneuver) {
                        "depart" -> tvIcon.setBackgroundResource(R.drawable.bg_step_icon_depart)
                        "arrive" -> tvIcon.setBackgroundResource(R.drawable.bg_step_icon_arrive)
                        else     -> tvIcon.setBackgroundResource(R.drawable.bg_step_icon)
                    }

                    // Tên đường: ưu tiên tên có nghĩa
                    tvStreet.text = when {
                        step.streetName.isNotEmpty() -> step.streetName
                        step.maneuver == "depart"    -> "Điểm xuất phát"
                        step.maneuver == "arrive"    -> "Điểm đến"
                        else                         -> "(Không có tên đường)"
                    }
                    tvStreet.setTextColor(
                        if (step.maneuver == "arrive")
                            getColor(R.color.accent_green)
                        else
                            getColor(R.color.text_primary)
                    )

                    tvInstruction.text = step.instruction

                    // Khoảng cách
                    if (step.distanceM > 0) {
                        tvDistance.text       = RouteHelper.formatDistance(step.distanceM)
                        tvDistance.visibility = View.VISIBLE
                    } else {
                        tvDistance.visibility = View.GONE
                    }

                    // Ẩn đường nối ở bước cuối
                    viewLine.visibility = if (index == meaningfulSteps.lastIndex)
                        View.INVISIBLE else View.VISIBLE

                    // Nền xen kẽ cho dễ đọc
                    itemView.setBackgroundColor(
                        if (index % 2 == 0) getColor(R.color.surface_card)
                        else getColor(R.color.bg_dark)
                    )

                    layoutSteps.addView(itemView)
                }

                scrollSteps.visibility = View.VISIBLE
            }
        }.start()
    }

    /** Trích modifier từ instruction đã dịch để xác định icon */
    private fun extractModifier(instruction: String): String = when {
        instruction.contains("trái")   -> "left"
        instruction.contains("phải")   -> "right"
        instruction.contains("thẳng")  -> "straight"
        instruction.contains("quay đầu") -> "uturn"
        else                           -> ""
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
            if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
        }
        return name
    }

    private fun updateMp3UI() {
        if (selectedMp3Uri != null) {
            binding.tvMp3Name.text    = "🎵 $selectedMp3Name"
            binding.btnRemoveMp3.visibility = View.VISIBLE
            binding.btnSelectMp3.text = "Đổi file MP3"
        } else {
            binding.tvMp3Name.text    = "Chưa chọn file (dùng âm thanh mặc định)"
            binding.btnRemoveMp3.visibility = View.GONE
            binding.btnSelectMp3.text = "Chọn file MP3"
        }
    }

    // ── Tracking ──────────────────────────────────────────────────────────────
    private fun startTracking() {
        if (!isBound || trackingService == null) {
            Toast.makeText(this, "Dịch vụ chưa sẵn sàng", Toast.LENGTH_SHORT).show(); return
        }
        if (!trackingService!!.hasTarget()) {
            Toast.makeText(this, "Vui lòng đặt vị trí đích trước", Toast.LENGTH_SHORT).show(); return
        }
        if (!hasLocationPermission()) { checkAndRequestPermissions(); return }
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
        binding.btnStopTracking.isEnabled  = isTracking
        binding.btnStartTracking.alpha = if (isTracking) 0.5f else 1f
        binding.btnStopTracking.alpha  = if (!isTracking) 0.5f else 1f
        if (isTracking) {
            binding.tvStatus.text = "📡 Đang theo dõi..."
            binding.tvStatus.setTextColor(getColor(R.color.accent_green))
            binding.indicatorTracking.visibility = View.VISIBLE
        } else {
            binding.indicatorTracking.visibility = View.GONE
        }
    }

    private fun restoreServiceState() {
        val (lat, lon, name) = PrefsHelper.loadTarget(this)
        if (lat != 0.0 && lon != 0.0) {
            binding.tvCoordinates.text = "🎯 %.6f, %.6f".format(lat, lon)
            binding.tvStatus.text      = "✅ Đích: $name"
            binding.tvStatus.setTextColor(getColor(R.color.accent_green))
            trackingService?.setTarget(lat, lon)
        }
        val radius = PrefsHelper.loadRadius(this)
        binding.sliderRadius.value     = radius
        binding.tvRadiusValue.text     = "${radius.toInt()}m"
        trackingService?.setAlertRadius(radius)
        if (trackingService?.isTracking() == true) updateTrackingUI(true)
        updateRouteButtonVisibility()
    }

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
}
