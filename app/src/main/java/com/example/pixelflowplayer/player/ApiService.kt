package com.example.pixelflowplayer.player

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// Request body sent by the app
// Request body sent to backend
data class HeartbeatRequest(
    val deviceId: String,
    val pairingCode: String = "", // default empty is OK
    val deviceInfo: DeviceInfo // Always include device information
)

// --- Display / Video ---
data class DisplayModeInfo(
    val widthPx: Int,
    val heightPx: Int,
    val refreshRate: Float
)

data class DisplayInfo(
    val currentWidthPx: Int,
    val currentHeightPx: Int,
    val densityDpi: Int,
    val refreshRate: Float,
    val rotation: Int,
    val supportedModes: List<DisplayModeInfo>? = null
)

data class VideoCodecSupport(
    val codec: String, // e.g., "video/avc", "video/hevc"
    val maxWidth: Int,
    val maxHeight: Int
)

// --- New system metrics ---
data class StorageInfo(
    val totalBytes: Long,
    val freeBytes: Long,
    val appCacheBytes: Long
)

data class MemoryInfoDto(
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val lowMemory: Boolean
)

data class BatteryInfo(
    val levelPercent: Int?,
    val charging: Boolean?,
    val powerSave: Boolean?
)

data class NetworkInfoDto(
    val online: Boolean,
    val transports: List<String>,
    val metered: Boolean?,
    val validated: Boolean?,
    val vpnActive: Boolean?
)

data class LocaleTimeInfo(
    val timezone: String,
    val locale: String,
    val is24h: Boolean,
    val uptimeMs: Long
)

data class PlayerStats(
    val playlistHash: String?,
    val itemCount: Int,
    val currentIndex: Int,
    val currentUrl: String?,
    val currentType: String?,
    val decoderName: String? = null,
    val droppedFrames: Int? = null,
    val lastDownloadErrors: List<String>? = null
)

data class DeviceInfo(
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val display: DisplayInfo? = null,
    val videoSupport: List<VideoCodecSupport>? = null,
    val storage: StorageInfo? = null,
    val memory: MemoryInfoDto? = null,
    val battery: BatteryInfo? = null,
    val network: NetworkInfoDto? = null,
    val localeTime: LocaleTimeInfo? = null,
    val playerStats: PlayerStats? = null
)

// Response body returned by backend
data class HeartbeatResponse(
    val status: String,
    val playlist: Playlist?, // nullable
    val rotation: Int?,      // nullable
    val command: String?,    // Professional command system (reset, generate_pairing_code, etc.)
    val message: String?     // Optional message from server
)

interface ApiService {
    @POST("devices/heartbeat")
    suspend fun deviceHeartbeat(@Body request: HeartbeatRequest): Response<HeartbeatResponse>
}

object ApiClient {
    // 🚀 ANDROID EMULATOR vs PHYSICAL DEVICE CONFIGURATION 🚀
    // 
    // 🤖 ANDROID EMULATOR (current setting):
    //     Use 10.0.2.2 - special alias to reach host machine's localhost
    //     The emulator runs in isolated network, can't see 127.0.0.1 directly
    //
    // 📱 PHYSICAL DEVICE:  
    //     Use 192.168.1.151 - actual WiFi IP of your computer
    //     Device connects to computer over same WiFi network
    //
    // 💡 TO SWITCH: Change HOST_IP below based on your testing method
    
    private const val HOST_IP = "10.0.2.2"        // 🤖 FOR EMULATOR
    // private const val HOST_IP = "192.168.1.104" // 📱 FOR PHYSICAL DEVICE (uncomment this line and comment above)
    
    private const val PORT = "3000"
    private const val BASE_URL = "http://$HOST_IP:$PORT/api/"

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)
    
    // Debug method to log the current configuration
    fun logConfiguration() {
        val environmentType = when (HOST_IP) {
            "10.0.2.2" -> "🤖 Android Emulator"
            "192.168.1.151" -> "📱 Physical Device (WiFi)"
            "localhost", "127.0.0.1" -> "❌ Invalid for Android"
            else -> "🌐 Custom Configuration"
        }
        
        println("PixelFlowPlayer API Configuration:")
        println("  Environment: $environmentType")
        println("  Host: $HOST_IP")
        println("  Port: $PORT") 
        println("  Base URL: $BASE_URL")
        println("  Server Target: ${if (HOST_IP == "10.0.2.2") "Host machine localhost via emulator bridge" else "Network server at $HOST_IP"}")
    }
}