package com.example.pixelflowplayer.player

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// Request body sent by the app
data class HeartbeatRequest(
    val deviceId: String,
    val pairingCode: String = "" // default empty is OK
)

// Response body returned by backend
data class HeartbeatResponse(
    val status: String,
    val playlist: Playlist?, // nullable
    val rotation: Int?       // nullable
)

interface ApiService {
    @POST("devices/heartbeat")
    suspend fun deviceHeartbeat(@Body request: HeartbeatRequest): Response<HeartbeatResponse>
}

object ApiClient {
    // Update to your backend IP/host if needed
    private const val BASE_URL = "http://192.168.1.151:3000/api/"

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)
}