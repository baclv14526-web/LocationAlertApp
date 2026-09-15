package com.locationalert

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit

/**
 * OkHttpClient singleton dùng chung toàn app.
 * Trước đây MainActivity và RouteHelper mỗi nơi tự tạo 1 client riêng,
 * lãng phí connection pool + thread pool. Gộp lại thành 1 instance duy nhất.
 *
 * Cấu hình TLS 3 lớp (fallback dần) để tương thích tốt với Android 9 (API 28):
 *   1. MODERN_TLS   — TLS 1.3 / 1.2, cipher mạnh (ưu tiên)
 *   2. COMPATIBLE_TLS — TLS cũ hơn, cipher rộng hơn (fallback)
 *   3. CLEARTEXT    — chỉ dùng nếu network_security_config cho phép (không áp dụng ở đây)
 */
object NetworkClient {

    val instance: OkHttpClient by lazy {
        val modernSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .allEnabledCipherSuites()
            .build()

        OkHttpClient.Builder()
            .connectionSpecs(listOf(modernSpec, ConnectionSpec.COMPATIBLE_TLS))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
