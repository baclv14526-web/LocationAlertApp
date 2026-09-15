package com.locationalert

import android.app.Application
import android.util.Log
import java.security.Security

/**
 * Application class — cài đặt Conscrypt làm Security Provider ưu tiên cao nhất
 * NGAY khi app khởi động, trước khi bất kỳ kết nối HTTPS nào được thực hiện.
 *
 * Tại sao cần Conscrypt:
 * Android 9 (API 28) dùng TLS stack cũ (dựa trên BoringSSL phiên bản cũ) có thể
 * không hỗ trợ đầy đủ cipher suite / TLS extension mà các server hiện đại yêu cầu
 * (ví dụ routing.openstreetmap.de, router.project-osrm.org). Kết quả là
 * "SSL handshake failed" dù code OkHttp đã cấu hình đúng.
 *
 * Conscrypt là TLS provider độc lập của Google (dùng trong chính Android runtime
 * ở bản mới), cài đặt qua thư viện sẽ ghi đè TLS stack mặc định của OS bằng
 * một implementation mới, đầy đủ, không phụ thuộc phiên bản Android.
 */
class LocationAlertApp : Application() {

    companion object {
        private const val TAG = "LocationAlertApp"
    }

    override fun onCreate() {
        super.onCreate()
        installConscrypt()
    }

    private fun installConscrypt() {
        try {
            val provider = org.conscrypt.Conscrypt.newProvider()
            // insertProviderAt(provider, 1) đặt Conscrypt làm provider ưu tiên
            // CAO NHẤT — mọi SSLContext/HttpsURLConnection sẽ dùng Conscrypt
            // thay vì TLS stack mặc định của Android 9.
            Security.insertProviderAt(provider, 1)
            Log.i(TAG, "Conscrypt TLS provider installed successfully")
        } catch (e: Exception) {
            // Nếu cài thất bại (hiếm khi xảy ra), app vẫn chạy được với TLS
            // mặc định của OS — chỉ là có thể gặp lại lỗi handshake trên
            // Android cũ với một số server nhất định.
            Log.e(TAG, "Failed to install Conscrypt provider: ${e.message}", e)
        }
    }
}
