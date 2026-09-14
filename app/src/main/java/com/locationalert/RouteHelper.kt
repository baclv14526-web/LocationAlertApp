package com.locationalert

import org.json.JSONObject

/**
 * Lấy danh sách tên đường từ vị trí hiện tại → đích đến
 * Dùng OSRM public API (miễn phí, không cần API key)
 * Endpoint: https://router.project-osrm.org/route/v1/driving/
 */
object RouteHelper {

    data class RouteStep(
        val streetName: String,   // Tên đường (có thể trống)
        val instruction: String,  // Hướng dẫn: "Rẽ trái", "Đi thẳng"...
        val distanceM: Int,       // Khoảng cách bước này (mét)
        val maneuver: String      // "turn-left", "straight", "arrive"...
    )

    data class RouteResult(
        val steps: List<RouteStep>,
        val totalDistanceM: Int,
        val totalDurationSec: Int,
        val error: String? = null
    )

    /** Gọi OSRM, parse steps, trả về RouteResult (chạy trên background thread) */
    fun fetchRoute(
        fromLat: Double, fromLon: Double,
        toLat: Double,   toLon: Double
    ): RouteResult {
        return try {
            // OSRM format: lon,lat (chú ý thứ tự lon trước lat)
            val url = java.net.URL(
                "https://router.project-osrm.org/route/v1/driving/" +
                "$fromLon,$fromLat;$toLon,$toLat" +
                "?steps=true&annotations=false&geometries=geojson&overview=false&language=vi"
            )
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("User-Agent", "LocationAlertApp/1.0")
                connectTimeout = 15_000
                readTimeout    = 15_000
            }

            if (conn.responseCode != 200) {
                return RouteResult(emptyList(), 0, 0, "Lỗi server: ${conn.responseCode}")
            }

            val body = conn.inputStream.bufferedReader().readText()
            parseOsrmResponse(body)

        } catch (e: Exception) {
            RouteResult(emptyList(), 0, 0, "Lỗi kết nối: ${e.message}")
        }
    }

    private fun parseOsrmResponse(json: String): RouteResult {
        val root  = JSONObject(json)
        val code  = root.optString("code", "")
        if (code != "Ok") {
            return RouteResult(emptyList(), 0, 0, "Không tìm được đường đi (code=$code)")
        }

        val route    = root.getJSONArray("routes").getJSONObject(0)
        val totalDist = route.optInt("distance", 0)
        val totalDur  = route.optInt("duration", 0)

        val legs  = route.getJSONArray("legs")
        val steps = mutableListOf<RouteStep>()

        for (li in 0 until legs.length()) {
            val legSteps = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until legSteps.length()) {
                val step      = legSteps.getJSONObject(si)
                val name      = step.optString("name", "").trim()
                val distM     = step.optDouble("distance", 0.0).toInt()
                val maneuver  = step.optJSONObject("maneuver")
                val mType     = maneuver?.optString("type", "")    ?: ""
                val mModifier = maneuver?.optString("modifier", "") ?: ""

                // Bỏ qua bước cuối arrive nếu distance = 0
                if (mType == "arrive" && distM == 0) {
                    // Vẫn thêm nhưng đánh dấu arrive
                    steps.add(RouteStep(name, buildInstruction(mType, mModifier, name), distM, mType))
                    continue
                }

                steps.add(
                    RouteStep(
                        streetName  = name,
                        instruction = buildInstruction(mType, mModifier, name),
                        distanceM   = distM,
                        maneuver    = mType
                    )
                )
            }
        }

        return RouteResult(steps, totalDist, totalDur)
    }

    /** Dịch maneuver type + modifier sang tiếng Việt */
    private fun buildInstruction(type: String, modifier: String, name: String): String {
        val dir = when (modifier) {
            "left"       -> "trái"
            "right"      -> "phải"
            "slight left"  -> "nhẹ trái"
            "slight right" -> "nhẹ phải"
            "sharp left"   -> "gấp trái"
            "sharp right"  -> "gấp phải"
            "uturn"      -> "quay đầu"
            "straight"   -> "thẳng"
            else         -> ""
        }
        return when (type) {
            "depart"      -> if (name.isNotEmpty()) "Xuất phát từ $name" else "Xuất phát"
            "arrive"      -> if (name.isNotEmpty()) "Đến nơi tại $name" else "Đến nơi"
            "turn"        -> if (dir.isNotEmpty()) "Rẽ $dir" else "Rẽ"
            "new name"    -> if (dir.isNotEmpty()) "Tiếp tục $dir" else "Tiếp tục"
            "continue"    -> "Đi thẳng"
            "merge"       -> if (dir.isNotEmpty()) "Nhập làn $dir" else "Nhập làn"
            "on ramp"     -> if (dir.isNotEmpty()) "Lên đường $dir" else "Lên đường"
            "off ramp"    -> if (dir.isNotEmpty()) "Ra đường $dir" else "Ra đường"
            "fork"        -> if (dir.isNotEmpty()) "Đi $dir tại ngã rẽ" else "Tại ngã rẽ"
            "end of road" -> if (dir.isNotEmpty()) "Rẽ $dir cuối đường" else "Cuối đường"
            "roundabout"  -> "Vào vòng xuyến"
            "rotary"      -> "Vào vòng xuyến"
            "roundabout turn" -> if (dir.isNotEmpty()) "Trong vòng xuyến, rẽ $dir" else "Trong vòng xuyến"
            "use lane"    -> "Giữ làn đường"
            else          -> if (dir.isNotEmpty()) "Đi $dir" else "Tiếp tục"
        }
    }

    /** Format khoảng cách đẹp: 950 → "950m", 1200 → "1.2km" */
    fun formatDistance(meters: Int): String = when {
        meters < 1000 -> "${meters}m"
        else          -> "${"%.1f".format(meters / 1000.0)}km"
    }

    /** Format thời gian: 3750s → "1 giờ 2 phút" */
    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0  -> "${h} giờ ${m} phút"
            m > 0  -> "${m} phút"
            else   -> "< 1 phút"
        }
    }

    /** Icon mũi tên theo maneuver */
    fun maneuverIcon(maneuver: String, modifier: String): String = when {
        maneuver == "depart"               -> "🚦"
        maneuver == "arrive"               -> "🏁"
        maneuver == "roundabout"
            || maneuver == "rotary"        -> "🔄"
        modifier.contains("left")          -> "↰"
        modifier.contains("right")         -> "↱"
        modifier == "uturn"                -> "↩"
        modifier == "straight"
            || maneuver == "continue"      -> "↑"
        else                               -> "➤"
    }
}
