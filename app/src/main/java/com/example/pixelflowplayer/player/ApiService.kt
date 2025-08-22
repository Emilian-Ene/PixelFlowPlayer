package com.example.pixelflowplayer.player

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// Data class for the request body the app will SEND
data class HeartbeatRequest(
    val deviceId: String,
    val pairingCode: String
)

// Data class for the response the app EXPECTS to receive
data class HeartbeatResponse(
    val status: String,
    val playlist: Playlist? // The '?' means this can be null
)

// The interface defining our API endpoint
interface ApiService {
    @POST("api/devices/heartbeat")
    suspend fun deviceHeartbeat(@Body request: HeartbeatRequest): Response<HeartbeatResponse>
}

// A separate object to create and provide the ApiService instance
object ApiClient {
    // IMPORTANT: Make sure this is your correct backend address and port
    private const val BASE_URL = "http://10.0.2.2:5000/"

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    // This is the variable that MainActivity will access
    val apiService: ApiService = retrofit.create(ApiService::class.java)
}