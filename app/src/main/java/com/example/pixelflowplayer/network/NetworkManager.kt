package com.example.pixelflowplayer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Enhanced network manager with intelligent retry mechanisms and network quality assessment
 */
class NetworkManager(private val context: Context) {
    
    companion object {
        private const val TAG = "NetworkManager"
        private const val TIMEOUT_MS = 10000L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val MIN_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 30000L
        private const val BACKOFF_MULTIPLIER = 2.0f
        private const val NETWORK_QUALITY_TEST_SIZE = 1024 // 1KB test
        private const val NETWORK_CHECK_INTERVAL_MS = 5000L
    }
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    // Network state tracking
    private val _networkState = MutableStateFlow(NetworkState.UNKNOWN)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()
    
    private val _networkQuality = MutableStateFlow(NetworkQuality.UNKNOWN)
    val networkQuality: StateFlow<NetworkQuality> = _networkQuality.asStateFlow()
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityCheckJob: Job? = null
    
    init {
        startNetworkMonitoring()
    }
    
    /**
     * Execute network request with intelligent retry mechanism
     */
    suspend fun <T> executeWithRetry(
        operation: suspend () -> NetworkResult<T>,
        maxRetries: Int = MAX_RETRY_ATTEMPTS,
        shouldRetry: (Exception) -> Boolean = ::defaultShouldRetry
    ): NetworkResult<T> = withContext(Dispatchers.IO) {
        var lastResult: NetworkResult<T>? = null
        var attempt = 0
        
        while (attempt <= maxRetries) {
            try {
                // Check network availability before attempting
                if (!isNetworkAvailable()) {
                    Log.w(TAG, "No network available for request (attempt ${attempt + 1})")
                    delay(calculateRetryDelay(attempt))
                    attempt++
                    continue
                }
                
                Log.d(TAG, "Attempting network request (attempt ${attempt + 1}/${maxRetries + 1})")
                val result = withTimeout(TIMEOUT_MS) { operation() }
                
                return@withContext when (result) {
                    is NetworkResult.Success -> {
                        Log.d(TAG, "Network request successful on attempt ${attempt + 1}")
                        result
                    }
                    is NetworkResult.Error -> {
                        lastResult = result
                        if (attempt < maxRetries && shouldRetry(result.exception)) {
                            Log.w(TAG, "Request failed (attempt ${attempt + 1}), retrying: ${result.exception.message}")
                            delay(calculateRetryDelay(attempt))
                            attempt++
                            continue
                        } else {
                            result
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Network error on attempt ${attempt + 1}", e)
                lastResult = NetworkResult.Error(e)
                
                if (attempt < maxRetries && shouldRetry(e)) {
                    delay(calculateRetryDelay(attempt))
                    attempt++
                    continue
                } else {
                    break
                }
            }
        }
        
        return@withContext lastResult ?: NetworkResult.Error(Exception("Request failed after $maxRetries attempts"))
    }
    
    /**
     * Download file with retry mechanism
     */
    suspend fun downloadWithRetry(
        url: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
        maxRetries: Int = MAX_RETRY_ATTEMPTS
    ): NetworkResult<ByteArray> = withContext(Dispatchers.IO) {
        
        return@withContext executeWithRetry(
            operation = { downloadFile(url, onProgress) },
            maxRetries = maxRetries
        )
    }
    
    /**
     * Check network connectivity
     */
    fun isNetworkAvailable(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network availability", e)
            false
        }
    }
    
    /**
     * Get current network quality assessment
     */
    suspend fun assessNetworkQuality(): NetworkQuality = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable()) {
            return@withContext NetworkQuality.NO_CONNECTION
        }
        
        try {
            val startTime = System.currentTimeMillis()
            
            // Test download a small amount of data
            val connection = java.net.URL("https://www.google.com").openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val inputStream = connection.getInputStream()
            val buffer = ByteArray(NETWORK_QUALITY_TEST_SIZE)
            var totalRead = 0
            var bytesRead: Int
            
            while (totalRead < NETWORK_QUALITY_TEST_SIZE) {
                bytesRead = inputStream.read(buffer, totalRead, NETWORK_QUALITY_TEST_SIZE - totalRead)
                if (bytesRead == -1) break
                totalRead += bytesRead
            }
            
            inputStream.close()
            val duration = System.currentTimeMillis() - startTime
            
            // Assess quality based on time taken
            return@withContext when {
                duration < 1000 -> NetworkQuality.EXCELLENT
                duration < 2500 -> NetworkQuality.GOOD
                duration < 5000 -> NetworkQuality.FAIR
                else -> NetworkQuality.POOR
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Network quality assessment failed", e)
            return@withContext NetworkQuality.POOR
        }
    }
    
    /**
     * Get current network state
     */
    fun getNetworkState(): NetworkState = _networkState.value
    
    /**
     * Start monitoring network connectivity
     */
    fun startNetworkMonitoring() {
        // Set up network callback
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network became available")
                _networkState.value = NetworkState.CONNECTED
                
                // Assess quality when network becomes available
                CoroutineScope(Dispatchers.IO).launch {
                    val quality = assessNetworkQuality()
                    _networkQuality.value = quality
                    Log.d(TAG, "Network quality assessed: $quality")
                }
            }
            
            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                _networkState.value = NetworkState.DISCONNECTED
                _networkQuality.value = NetworkQuality.NO_CONNECTION
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // Re-assess quality when capabilities change
                CoroutineScope(Dispatchers.IO).launch {
                    val quality = assessNetworkQuality()
                    _networkQuality.value = quality
                }
            }
        }
        
        // Register callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        networkCallback?.let { connectivityManager.registerNetworkCallback(networkRequest, it) }
        
        // Set initial state
        _networkState.value = if (isNetworkAvailable()) NetworkState.CONNECTED else NetworkState.DISCONNECTED
        
        // Start periodic quality monitoring
        connectivityCheckJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    if (isNetworkAvailable()) {
                        val quality = assessNetworkQuality()
                        _networkQuality.value = quality
                    }
                    delay(NETWORK_CHECK_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in connectivity monitoring", e)
                    delay(NETWORK_CHECK_INTERVAL_MS)
                }
            }
        }
    }
    
    /**
     * Calculate retry delay with exponential backoff
     */
    private fun calculateRetryDelay(attempt: Int): Long {
        val delay = (MIN_RETRY_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER.toDouble(), attempt.toDouble())).toLong()
        return minOf(delay, MAX_RETRY_DELAY_MS)
    }
    
    /**
     * Default retry logic based on exception type
     */
    private fun defaultShouldRetry(exception: Exception): Boolean {
        return when (exception) {
            is SocketTimeoutException,
            is IOException,
            is UnknownHostException -> true
            else -> false
        }
    }
    
    /**
     * Download file implementation
     */
    private suspend fun downloadFile(
        url: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): NetworkResult<ByteArray> {
        return withContext(Dispatchers.IO) {
            try {
                val connection = java.net.URL(url).openConnection()
                connection.connectTimeout = TIMEOUT_MS.toInt()
                connection.readTimeout = TIMEOUT_MS.toInt()
                
                val contentLength = connection.contentLengthLong
                val inputStream = connection.getInputStream()
                val byteArrayOutputStream = java.io.ByteArrayOutputStream()
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    onProgress(totalBytesRead, contentLength)
                }
                
                inputStream.close()
                val responseBody = byteArrayOutputStream.toByteArray()
                
                NetworkResult.Success(responseBody)
                
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $url", e)
                NetworkResult.Error(e)
            }
        }
    }
    
    /**
     * Stop network monitoring
     */
    fun stopNetworkMonitoring() {
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        connectivityCheckJob?.cancel()
        networkCallback = null
        connectivityCheckJob = null
        Log.d(TAG, "Network monitoring stopped")
    }
}

/**
 * Network result wrapper
 */
sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val exception: Exception) : NetworkResult<Nothing>()
}

/**
 * Network connectivity states
 */
enum class NetworkState {
    CONNECTED,
    DISCONNECTED,
    UNKNOWN
}

/**
 * Network quality levels
 */
enum class NetworkQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    NO_CONNECTION,
    UNKNOWN
}