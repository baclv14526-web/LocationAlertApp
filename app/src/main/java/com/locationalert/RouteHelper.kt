package com.locationalert

import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

/**
 * Lấy danh sách tên đường từ vị trí hiện tại → đích đến
 * Primary  : OSRM public API  (router.project-osrm.org)
 * Fallback : OSRM DE mirror   (routing.openstreetmap.de)
 *
 * Fix TLS handshake failed trên Android 9 (API 28):
 *  - Khởi tạo SSLContext tường minh với TrustManager hệ thống
 *  - Retry tự động sang mirror khi primary lỗi
 */
object RouteHelper {

    // ── Endpoints (primary + fallback) ───────────────────────────────────────
    private val OSRM_ENDPOINTS = listOf(
        "https://router.project-osrm.org/route/v1/driving/",
        "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
    )

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

    // ── SSLContext cố định cho Android 9 ─────────────────────────────────────
    private fun buildSslContext(): SSLContext {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)                 // dùng system trust store
        return SSLContext.getInstance("TLSv1.2").also {
            it.init(null, tmf.trustManagers, null)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────
    fun fetchRoute(
        fromLat: Double, fromLon: Double,
        toLat: Double,   toLon: Double
    ): RouteResult {
        val coords = "$fromLon,$fromLat;$toLon,$toLat"
        val params = "?steps=true&annotations=false&overview=false"
        var lastError = "Không có kết nối"

        // Thử lần lượt từng endpoint
        for (base in OSRM_ENDPOINTS) {
            try {
                val result = fetchFromEndpoint("$base$coords$params")
                if (result.error == null) return result   // thành công
                lastError = result.error
            } catch (e: Exception) {
                lastError = simplifyError(e)
                // Tiếp tục thử endpoint tiếp theo
            }
        }

        return RouteResult(emptyList(), 0, 0, lastError)
    }

    // ── Internal fetch ────────────────────────────────────────────────────────
    private fun fetchFromEndpoint(urlStr: String): RouteResult {
        val url  = URL(urlStr)
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            // Fix TLS handshake trên Android 9
            sslSocketFactory = buildSslContext().socketFactory
            setRequestProperty("User-Agent", "LocationAlertApp/1.0 Android")
            setRequestProperty("Accept",     "application/json")
            connectTimeout = 20_000
            readTimeout    = 20_000
            requestMethod  = "GET"
            instanceFollowRedirects = true
        }

        val code = conn.responseCode
        if (code != 200) {
            return RouteResult(emptyList(), 0, 0, "Server lỗi: HTTP $code")
        }

        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        return parseOsrmResponse(body)
    }

    // ── Parser ────────────────────────────────────────────────────────────────
    private fun parseOsrmResponse(json: String): RouteResult {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") {
            val msg = root.optString("message", "Không tìm được đường")
            return RouteResult(emptyList(), 0, 0, msg)
        }

        val route     = root.getJSONArray("routes").getJSONObject(0)
        val totalDist = route.optInt("distance", 0)
        val totalDur  = route.optInt("duration", 0)
        val legs      = route.getJSONArray("legs")
        val steps     = mutableListOf<RouteStep>()

        for (li in 0 until legs.length()) {
            val legSteps = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until legSteps.length()) {
                val step      = legSteps.getJSONObject(si)
                val name      = step.optString("name", "").trim()
                val distM     = step.optDouble("distance", 0.0).toInt()
                val maneuver  = step.optJSONObject("maneuver")
                val mType     = maneuver?.optString("type", "")     ?: ""
                val mModifier = maneuver?.optString("modifier", "") ?: ""

                steps.add(RouteStep(
                    streetName  = name,
                    instruction = buildInstruction(mType, mModifier, name),
                    distanceM   = distM,
                    maneuver    = mType,
                    modifier    = mModifier
                ))
            }
        }

        return RouteResult(steps, totalDist, totalDur)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun simplifyError(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("handshake", ignoreCase = true)  -> "Lỗi bảo mật SSL (handshake) — thử lại"
            msg.contains("timeout",   ignoreCase = true)  -> "Quá thời gian chờ — kiểm tra mạng"
            msg.contains("host",      ignoreCase = true)  -> "Không kết nối được server"
            msg.contains("network",   ignoreCase = true)  -> "Không có kết nối mạng"
            else -> "Lỗi: $msg"
        }
    }

    private fun buildInstruction(type: String, modifier: String, name: String): String {
        val dir = when (modifier) {
            "left"        -> "trái"
            "right"       -> "phải"
            "slight left" -> "nhẹ trái"
            "slight right"-> "nhẹ phải"
            "sharp left"  -> "gấp trái"
            "sharp right" -> "gấp phải"
            "uturn"       -> "quay đầu"
            "straight"    -> "thẳng"
            else          -> ""
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
            "roundabout",
            "rotary"          -> "Vào vòng xuyến"
            "roundabout turn" -> if (dir.isNotEmpty())  "Trong vòng xuyến, rẽ $dir" else "Trong vòng xuyến"
            "use lane"        -> "Giữ làn đường"
            else              -> if (dir.isNotEmpty())  "Đi $dir"           else "Tiếp tục"
        }
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
        maneuver == "depart"                        -> "🚦"
        maneuver == "arrive"                        -> "🏁"
        maneuver == "roundabout" || maneuver == "rotary" -> "🔄"
        modifier.contains("left")                   -> "↰"
        modifier.contains("right")                  -> "↱"
        modifier == "uturn"                         -> "↩"
        modifier == "straight" || maneuver == "continue" -> "↑"
        else                                        -> "➤"
    }
}
