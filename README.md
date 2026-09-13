# 📍 Location Alert App

Ứng dụng Android định vị và cảnh báo đến nơi — viết bằng **Kotlin + Android SDK**.  
Hỗ trợ GPS, WiFi và mạng di động. Chạy tốt trên **Realme 2 (Android 9+)**.

---

## ✨ Tính năng

| Tính năng | Chi tiết |
|-----------|----------|
| 📡 Định vị đa nguồn | GPS + WiFi + Mạng di động (FusedLocationProvider) |
| 🎤 Nhập địa chỉ | Giọng nói (Tiếng Việt) hoặc gõ tay |
| 📌 Tọa độ thủ công | Nhập lat/lon trực tiếp |
| 🔔 Cảnh báo 20–30m | Rung + âm thanh ngay khi đến gần |
| 🎵 Âm thanh tùy chọn | Chọn file MP3 hoặc dùng âm mặc định |
| 🗑️ Xóa MP3 | Xóa file đã chọn, quay về mặc định |
| ⏹ Dừng/bắt đầu | Nút điều khiển theo dõi |
| 🌙 Nền tối | Giao diện Dark Mode tối ưu pin |

---

## 📦 Cài đặt & Build

### Yêu cầu
- Android Studio **Hedgehog 2023.1** trở lên
- JDK 17
- Google Play Services trên thiết bị

### Bước 1: Thêm Google Maps API Key
Trong `app/src/main/AndroidManifest.xml`, thêm:
```xml
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="YOUR_API_KEY_HERE" />
```
> ⚠️ API key Maps chỉ cần nếu bạn muốn hiển thị bản đồ. Geocoding dùng **Nominatim OSM** — **miễn phí, không cần key**.

### Bước 2: Mở project
```
File → Open → chọn thư mục LocationAlertApp
```

### Bước 3: Sync & Build
```
Build → Make Project  (Ctrl+F9)
Run → Run 'app'       (Shift+F10)
```

---

## 🔐 Quyền cần cấp (tự động xin khi chạy lần đầu)

| Quyền | Mục đích |
|-------|----------|
| `ACCESS_FINE_LOCATION` | GPS chính xác |
| `ACCESS_COARSE_LOCATION` | WiFi/mạng di động |
| `ACCESS_BACKGROUND_LOCATION` | Theo dõi khi tắt màn hình |
| `RECORD_AUDIO` | Nhập giọng nói |
| `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` | Chọn file MP3 |
| `VIBRATE` | Rung cảnh báo |
| `POST_NOTIFICATIONS` | Thông báo (Android 13+) |

---

## 🏗️ Kiến trúc

```
MainActivity
├── UI (ViewBinding)
│   ├── Nhập địa chỉ (text / giọng nói)
│   ├── Geocoding (Nominatim OSM API)
│   ├── Chọn/xóa file MP3
│   ├── Slider bán kính cảnh báo (20–30m)
│   └── Start/Stop theo dõi
│
LocationTrackingService (ForegroundService)
├── FusedLocationProviderClient
│   └── Priority.HIGH_ACCURACY (GPS + WiFi + Cell)
├── Tính khoảng cách (Location.distanceBetween)
├── Trigger khi distance ≤ alertRadius
│   ├── Rung (pattern: 500ms × 4)
│   ├── MediaPlayer (MP3 hoặc alarm mặc định)
│   └── Notification HIGH_PRIORITY
└── Cooldown 15 giây (tránh spam)

PrefsHelper (SharedPreferences)
└── Lưu: target lat/lon, radius
```

---

## 📱 Giao diện

```
┌─────────────────────────────┐
│  📍 Location Alert    [●]   │  ← Dot xanh = đang tracking
├─────────────────────────────┤
│  VỊ TRÍ HIỆN TẠI           │
│  📍 21.027763, 105.834160   │
│  Khoảng cách: 342m          │
├─────────────────────────────┤
│  VỊ TRÍ ĐÍCH               │
│  [___địa chỉ___] [🎤] [✕]  │
│  [🔍 Tìm]  [📌 Tọa độ]     │
│  🎯 21.0312, 105.8516       │
│  ✅ Đã đặt: Hồ Hoàn Kiếm   │
├─────────────────────────────┤
│  CÀI ĐẶT CẢNH BÁO          │
│  Bán kính: ──●── 25m        │
│  🎵 nhac.mp3        [Xóa]   │
│  [Chọn file MP3]            │
├─────────────────────────────┤
│  [▶ BẮT ĐẦU THEO DÕI  ]    │
│  [⏹ DỪNG THEO DÕI     ]    │
└─────────────────────────────┘
```

---

## ⚙️ Điều chỉnh ngưỡng cảnh báo

Mặc định: **20m – 30m** (slider trong app).  
Để thay đổi cứng trong code, sửa `LocationTrackingService.kt`:
```kotlin
const val DEFAULT_RADIUS = 25f   // mặc định
const val MIN_RADIUS = 20f       // slider min
const val MAX_RADIUS = 30f       // slider max
```

---

## 📝 Lưu ý

- Geocoding dùng **Nominatim (OpenStreetMap)** — cần kết nối internet để tìm địa chỉ
- Nhập tọa độ thủ công hoạt động **offline hoàn toàn**
- Trên Android 10+: cần cấp quyền "Luôn luôn" cho vị trí để theo dõi khi tắt màn hình
- File MP3 lưu bằng `persistableUriPermission` — không mất sau khi khởi động lại
