package com.locationalert

import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * OkHttpClient singleton dùng chung toàn app.
 *
 * Fix SSL handshake trên Android 9 (API 28):
 *   1. Conscrypt được cài làm Security Provider ưu tiên cao nhất trong
 *      LocationAlertApp.onCreate() — đây là fix chính, thay thế TLS stack
 *      cũ/lỗi của OS bằng implementation đầy đủ của Google.
 *   2. SSLContext ở đây được khởi tạo TƯỜNG MINH bằng SSLContext.getInstance("TLS"),
 *      để đảm bảo nó thực sự lấy Conscrypt provider (vừa được insert ở vị trí 1)
 *      thay vì cache provider cũ từ trước khi Conscrypt được cài.
 *   3. ConnectionSpec 2 lớp (MODERN_TLS → COMPATIBLE_TLS) vẫn giữ để dự phòng
 *      trường hợp Conscrypt cài thất bại.
 */
object NetworkClient {

    private const val TAG = "NetworkClient"

    val instance: OkHttpClient by lazy { buildClient() }

    private fun buildClient(): OkHttpClient {
        val modernSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .allEnabledCipherSuites()
            .build()

        val builder = OkHttpClient.Builder()
            .connectionSpecs(listOf(modernSpec, ConnectionSpec.COMPATIBLE_TLS))
            // 10s thay vì 20s — OSRM API thường phản hồi <3s khi hoạt động
            // bình thường. Với 2 endpoint thử tuần tự, 20s/endpoint nghĩa là
            // worst-case tới 40s treo dialog nếu cả 2 đều chậm/không phản
            // hồi. 10s vẫn đủ rộng rãi cho mạng di động chậm, giảm worst-case
            // xuống còn 20s.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        // Khởi tạo SSLContext tường minh — lấy đúng Conscrypt provider
        // (đã được insert ở vị trí ưu tiên cao nhất trong LocationAlertApp)
        try {
            val trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagerFactory.init(null as KeyStore?)
            val trustManagers = trustManagerFactory.trustManagers
            val x509TrustManager = trustManagers.firstOrNull { it is X509TrustManager }
                as? X509TrustManager

            if (x509TrustManager != null) {
                // "TLS" (không ghi version cụ thể) để SSLContext tự chọn
                // provider có sẵn tốt nhất — chính là Conscrypt sau khi cài
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(x509TrustManager), SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, x509TrustManager)
                Log.i(TAG, "Custom SSLContext initialized (provider: ${sslContext.provider.name})")
            }
        } catch (e: Exception) {
            // Nếu lỗi, OkHttp vẫn dùng SSLContext mặc định của hệ thống —
            // Conscrypt (nếu cài thành công) vẫn có hiệu lực vì nó được
            // insert ở cấp Security Provider toàn hệ thống, không chỉ ở đây.
            Log.w(TAG, "Custom SSLContext setup failed, falling back to system default: ${e.message}")
        }

        return builder.build()
    }
}
