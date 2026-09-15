package com.locationalert

import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Routing helper dùng OkHttp — xử lý TLS đúng trên Android 9 (API 28).
 *
 * Chiến lược endpoint:
 *   1. OSRM public   (router.project-osrm.org)
 *   2. OSRM DE mirror (routing.openstreetmap.de)
 *
 * OkHttp tự động:
 *   - Negotiate TLS 1.2 / 1.3 phù hợp với từng server
 *   - Retry khi connection reset
 *   - Không bị vấn đề SSLHandshakeException như URLConnection
 */
object RouteHelper {

    private const val TAG = "RouteHelper"

    // ── OkHttpClient dùng chung (singleton, thread-safe) ─────────────────────
    private val client: OkHttpClient by lazy {
        val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .allEnabledCipherSuites()
            .build()

        OkHttpClient.Builder()
            .connectionSpecs(listOf(spec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val ENDPOINTS = listOf(
        "https://router.project-osrm.org/route/v1/driving/",
        "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
    )

    // ── Data classes ──────────────────────────────────────────────────────────
    data class RouteStep(
        val streetName: String,
        val instruction: String,
        val distanceM: Int,
        val maneuver: String,
        val modifier: String = ""
    )

    data class RouteResult(
        val steps: List<RouteStep>,
        val totalDistanceM: Int,
        val totalDurationSec: Int,
        val error: String? = null
    )

    // ── Public API ────────────────────────────────────────────────────────────
    fun fetchRoute(
        fromLat: Double, fromLon: Double,
        toLat: Double,   toLon: Double
    ): RouteResult {
        val coords = "$fromLon,$fromLat;$toLon,$toLat"
        val params = "?steps=true&annotations=false&overview=false"
        val errors = mutableListOf<String>()

        for (base in ENDPOINTS) {
            val url = "$base$coords$params"
            Log.d(TAG, "Trying endpoint: $url")
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "LocationAlertApp/1.0 Android")
                    .header("Accept",     "application/json")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "HTTP ${response.code} from ${base.host()}")
                    if (!response.isSuccessful) {
                        errors += "HTTP ${response.code} từ ${base.host()}"
                        return@use
                    }
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        errors += "Phản hồi rỗng từ ${base.host()}"
                        return@use
                    }
                    val result = parseOsrm(body)
                    if (result.error == null) {
                        Log.d(TAG, "OK — ${result.steps.size} steps, ${result.totalDistanceM}m")
                        return result          // ← thành công, trả về ngay
                    }
                    errors += result.error
                }
            } catch (e: Exception) {
                val msg = friendlyError(e, base.host())
                Log.e(TAG, msg, e)
                errors += msg
            }
        }

        return RouteResult(emptyList(), 0, 0, errors.joinToString("\n"))
    }

    // ── Parse OSRM JSON ───────────────────────────────────────────────────────
    private fun parseOsrm(json: String): RouteResult {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok")
            return RouteResult(emptyList(), 0, 0,
                root.optString("message", "Không tìm được đường đi"))

        val route     = root.getJSONArray("routes").getJSONObject(0)
        val totalDist = route.optInt("distance", 0)
        val totalDur  = route.optInt("duration", 0)
        val legs      = route.getJSONArray("legs")
        val steps     = mutableListOf<RouteStep>()

        for (li in 0 until legs.length()) {
            val legSteps = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until legSteps.length()) {
                val s   = legSteps.getJSONObject(si)
                val man = s.optJSONObject("maneuver")
                val typ = man?.optString("type", "")     ?: ""
                val mod = man?.optString("modifier", "") ?: ""
                val nm  = s.optString("name", "").trim()
                steps += RouteStep(
                    streetName  = nm,
                    instruction = buildInstruction(typ, mod, nm),
                    distanceM   = s.optDouble("distance", 0.0).toInt(),
                    maneuver    = typ,
                    modifier    = mod
                )
            }
        }
        return RouteResult(steps, totalDist, totalDur)
    }

    // ── Instruction → tiếng Việt ──────────────────────────────────────────────
    private fun buildInstruction(type: String, modifier: String, name: String): String {
        val dir = when (modifier) {
            "left"         -> "trái"
            "right"        -> "phải"
            "slight left"  -> "nhẹ trái"
            "slight right" -> "nhẹ phải"
            "sharp left"   -> "gấp trái"
            "sharp right"  -> "gấp phải"
            "uturn"        -> "quay đầu"
            "straight"     -> "thẳng"
            else           -> ""
        }
        return when (type) {
            "depart"          -> if (name.isNotEmpty()) "Xuất phát từ $name" else "Xuất phát"
            "arrive"          -> if (name.isNotEmpty()) "Đến nơi: $name"    else "Đến nơi"
            "turn"            -> if (dir.isNotEmpty())  "Rẽ $dir"           else "Rẽ"
            "new name"        -> if (dir.isNotEmpty())  "Tiếp tục $dir"     else "Tiếp tục"
            "continue"        -> "Đi thẳng"
            "merge"           -> if (dir.isNotEmpty())  "Nhập làn $dir"     else "Nhập làn"
            "on ramp"         -> if (dir.isNotEmpty())  "Lên đường $dir"    else "Lên đường"
            "off ramp"        -> if (dir.isNotEmpty())  "Ra đường $dir"     else "Ra đường"
            "fork"            -> if (dir.isNotEmpty())  "Đi $dir tại ngã rẽ" else "Tại ngã rẽ"
            "end of road"     -> if (dir.isNotEmpty())  "Rẽ $dir cuối đường" else "Cuối đường"
            "roundabout","rotary" -> "Vào vòng xuyến"
            "roundabout turn" -> if (dir.isNotEmpty())  "Trong vòng xuyến, rẽ $dir" else "Trong vòng xuyến"
            "use lane"        -> "Giữ làn đường"
            else              -> if (dir.isNotEmpty())  "Đi $dir"           else "Tiếp tục"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun String.host() = removePrefix("https://").substringBefore("/")

    private fun friendlyError(e: Exception, host: String): String = when (e) {
        is javax.net.ssl.SSLException       -> "SSL lỗi ($host): ${e.message}"
        is java.net.SocketTimeoutException  -> "Timeout ($host) — kiểm tra mạng"
        is java.net.UnknownHostException    -> "Không có mạng / DNS lỗi"
        is java.io.IOException              -> "Mạng lỗi ($host): ${e.message}"
        else                               -> "${e.javaClass.simpleName}: ${e.message}"
    }

    fun formatDistance(meters: Int): String = when {
        meters < 1000 -> "${meters}m"
        else          -> "${"%.1f".format(meters / 1000.0)}km"
    }

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "${h} giờ ${m} phút"
            m > 0 -> "${m} phút"
            else  -> "< 1 phút"
        }
    }

    fun maneuverIcon(maneuver: String, modifier: String): String = when {
        maneuver == "depart"                             -> "🚦"
        maneuver == "arrive"                             -> "🏁"
        maneuver == "roundabout" || maneuver == "rotary" -> "🔄"
        modifier.contains("left")                        -> "↰"
        modifier.contains("right")                       -> "↱"
        modifier == "uturn"                              -> "↩"
        modifier == "straight" || maneuver == "continue" -> "↑"
        else                                             -> "➤"
    }
}
