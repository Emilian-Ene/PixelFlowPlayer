package com.example.pixelflowplayer

import android.app.Application
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.pixelflowplayer.network.WebSocketManager
import java.net.URI
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class PfpApp : Application() {
    companion object {
        private lateinit var INSTANCE: PfpApp
        fun instance(): PfpApp = INSTANCE
        fun wsManager(): WebSocketManager? = INSTANCE.ws
    }

    private var ws: WebSocketManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ensureRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        try {
            // Parse host/port from the same BASE_URL used in MainActivity
            val baseUrl = MainActivity.BASE_URL
            val uri = URI(baseUrl)
            val host = uri.host ?: "192.168.1.151"
            val port = if (uri.port > 0) uri.port else 3000
            val deviceId = getOrCreateDeviceId()
            ws = WebSocketManager.getInstance(host, port, deviceId)
            // Connect immediately
            try { ws?.connect() } catch (_: Exception) {}
            // Fallback: ensure connected after 5s (in case process still warming up)
            handler.postDelayed({
                try { ws?.ensureConnected() } catch (_: Exception) {}
            }, 5000)
            // Ensure loop: try every 4s for 60s after startup
            val endAt = System.currentTimeMillis() + 60_000L
            ensureRunnable = object : Runnable {
                override fun run() {
                    try { ws?.ensureConnected() } catch (_: Exception) {}
                    if (System.currentTimeMillis() < endAt) {
                        handler.postDelayed(this, 4000)
                    }
                }
            }
            handler.postDelayed(ensureRunnable!!, 4000)

            // Also hook network availability to re-ensure WS
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
                cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { try { ws?.ensureConnected() } catch (_: Exception) {} }
                    override fun onLost(network: Network) { try { ws?.ensureConnected() } catch (_: Exception) {} }
                })
            } catch (_: Exception) { }
            Log.i("PfpApp", "WS bootstrap scheduled host=$host port=$port deviceId=$deviceId")
        } catch (e: Exception) {
            Log.e("PfpApp", "WS bootstrap error: ${e.message}")
        }
    }

    private fun getOrCreateDeviceId(): String {
        return try {
            val prefs = getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE)
            val existing = prefs.getString("deviceId", null)
            if (!existing.isNullOrBlank()) return existing
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val model = Build.MANUFACTURER + "-" + Build.MODEL
            val newId = (androidId ?: (model + System.currentTimeMillis().toString())).replace(" ", "-")
            prefs.edit().putString("deviceId", newId).apply()
            newId
        } catch (e: Exception) {
            System.currentTimeMillis().toString()
        }
    }
}
