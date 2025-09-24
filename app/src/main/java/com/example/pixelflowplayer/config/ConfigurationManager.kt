package com.example.pixelflowplayer.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Enhanced configuration manager with secure storage and remote updates
 */
class ConfigurationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ConfigurationManager"
        private const val ENCRYPTED_PREFS_NAME = "pixelflow_secure_config"
        private const val REGULAR_PREFS_NAME = "pixelflow_config"
        
        // Configuration keys
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_IS_PAIRED = "is_paired"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_TIMEOUT = "api_timeout"
        const val KEY_DOWNLOAD_TIMEOUT = "download_timeout"
        const val KEY_MAX_CACHE_SIZE_MB = "max_cache_size_mb"
        const val KEY_AUTO_CLEANUP_ENABLED = "auto_cleanup_enabled"
        const val KEY_HARDWARE_ACCELERATION = "hardware_acceleration"
        const val KEY_PLAYBACK_BUFFER_MS = "playback_buffer_ms"
        const val KEY_LOG_LEVEL = "log_level"
        const val KEY_HEARTBEAT_INTERVAL_SEC = "heartbeat_interval_sec"
        const val KEY_OFFLINE_MODE_ENABLED = "offline_mode_enabled"
        const val KEY_NETWORK_RETRY_ATTEMPTS = "network_retry_attempts"
        const val KEY_SCREEN_BRIGHTNESS = "screen_brightness"
        const val KEY_VOLUME_LEVEL = "volume_level"
        const val KEY_ORIENTATION_LOCK = "orientation_lock"
        const val KEY_POWER_MANAGEMENT = "power_management_enabled"
        
        // Default values
        private const val DEFAULT_API_TIMEOUT = 30000L
        private const val DEFAULT_DOWNLOAD_TIMEOUT = 60000L
        private const val DEFAULT_MAX_CACHE_SIZE_MB = 2048L
        private const val DEFAULT_PLAYBACK_BUFFER_MS = 5000
        private const val DEFAULT_HEARTBEAT_INTERVAL_SEC = 15
        private const val DEFAULT_NETWORK_RETRY_ATTEMPTS = 3
        private const val DEFAULT_SCREEN_BRIGHTNESS = 100
        private const val DEFAULT_VOLUME_LEVEL = 80
    }
    
    private var encryptedPrefs: SharedPreferences? = null
    private val regularPrefs: SharedPreferences = context.getSharedPreferences(REGULAR_PREFS_NAME, Context.MODE_PRIVATE)
    
    init {
        initializeEncryptedStorage()
    }
    
    /**
     * Initialize encrypted storage for sensitive data
     */
    private fun initializeEncryptedStorage() {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            Log.d(TAG, "Encrypted storage initialized successfully")
            
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Failed to initialize encrypted storage", e)
        } catch (e: IOException) {
            Log.e(TAG, "IO error initializing encrypted storage", e)
        }
    }
    
    // Device Configuration
    fun getDeviceId(): String = getSecureString(KEY_DEVICE_ID) ?: ""
    fun setDeviceId(deviceId: String) = setSecureString(KEY_DEVICE_ID, deviceId)
    
    fun isPaired(): Boolean = getSecureBoolean(KEY_IS_PAIRED, false)
    fun setPaired(paired: Boolean) = setSecureBoolean(KEY_IS_PAIRED, paired)
    
    // Server Configuration
    fun getServerUrl(): String = getString(KEY_SERVER_URL, null) ?: ""
    fun setServerUrl(url: String) = setString(KEY_SERVER_URL, url)
    
    fun getApiTimeout(): Long = getLong(KEY_API_TIMEOUT, DEFAULT_API_TIMEOUT)
    fun setApiTimeout(timeout: Long) = setLong(KEY_API_TIMEOUT, timeout)
    
    fun getDownloadTimeout(): Long = getLong(KEY_DOWNLOAD_TIMEOUT, DEFAULT_DOWNLOAD_TIMEOUT)
    fun setDownloadTimeout(timeout: Long) = setLong(KEY_DOWNLOAD_TIMEOUT, timeout)
    
    // Storage Configuration  
    fun getMaxCacheSizeMB(): Long = getLong(KEY_MAX_CACHE_SIZE_MB, DEFAULT_MAX_CACHE_SIZE_MB)
    fun setMaxCacheSizeMB(sizeMB: Long) = setLong(KEY_MAX_CACHE_SIZE_MB, sizeMB)
    
    fun isAutoCleanupEnabled(): Boolean = getBoolean(KEY_AUTO_CLEANUP_ENABLED, true)
    fun setAutoCleanupEnabled(enabled: Boolean) = setBoolean(KEY_AUTO_CLEANUP_ENABLED, enabled)
    
    // Playback Configuration
    fun isHardwareAccelerationEnabled(): Boolean = getBoolean(KEY_HARDWARE_ACCELERATION, true)
    fun setHardwareAccelerationEnabled(enabled: Boolean) = setBoolean(KEY_HARDWARE_ACCELERATION, enabled)
    
    fun getPlaybackBufferMs(): Int = getInt(KEY_PLAYBACK_BUFFER_MS, DEFAULT_PLAYBACK_BUFFER_MS)
    fun setPlaybackBufferMs(bufferMs: Int) = setInt(KEY_PLAYBACK_BUFFER_MS, bufferMs)
    
    // Network Configuration
    fun getHeartbeatIntervalSec(): Int = getInt(KEY_HEARTBEAT_INTERVAL_SEC, DEFAULT_HEARTBEAT_INTERVAL_SEC)
    fun setHeartbeatIntervalSec(intervalSec: Int) = setInt(KEY_HEARTBEAT_INTERVAL_SEC, intervalSec)
    
    fun isOfflineModeEnabled(): Boolean = getBoolean(KEY_OFFLINE_MODE_ENABLED, true)
    fun setOfflineModeEnabled(enabled: Boolean) = setBoolean(KEY_OFFLINE_MODE_ENABLED, enabled)
    
    fun getNetworkRetryAttempts(): Int = getInt(KEY_NETWORK_RETRY_ATTEMPTS, DEFAULT_NETWORK_RETRY_ATTEMPTS)
    fun setNetworkRetryAttempts(attempts: Int) = setInt(KEY_NETWORK_RETRY_ATTEMPTS, attempts)
    
    // Display Configuration
    fun getScreenBrightness(): Int = getInt(KEY_SCREEN_BRIGHTNESS, DEFAULT_SCREEN_BRIGHTNESS)
    fun setScreenBrightness(brightness: Int) = setInt(KEY_SCREEN_BRIGHTNESS, brightness.coerceIn(1, 100))
    
    fun getVolumeLevel(): Int = getInt(KEY_VOLUME_LEVEL, DEFAULT_VOLUME_LEVEL)
    fun setVolumeLevel(volume: Int) = setInt(KEY_VOLUME_LEVEL, volume.coerceIn(0, 100))
    
    fun getOrientationLock(): String = getString(KEY_ORIENTATION_LOCK, "auto") ?: "auto"
    fun setOrientationLock(orientation: String) = setString(KEY_ORIENTATION_LOCK, orientation)
    
    fun isPowerManagementEnabled(): Boolean = getBoolean(KEY_POWER_MANAGEMENT, true)
    fun setPowerManagementEnabled(enabled: Boolean) = setBoolean(KEY_POWER_MANAGEMENT, enabled)
    
    // Logging Configuration
    fun getLogLevel(): String = getString(KEY_LOG_LEVEL, "INFO") ?: "INFO"
    fun setLogLevel(level: String) = setString(KEY_LOG_LEVEL, level)
    
    /**
     * Update configuration from remote server
     */
    suspend fun updateFromRemote(remoteConfig: Map<String, Any>) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Updating configuration from remote server")
        
        try {
            for ((key, value) in remoteConfig) {
                when (value) {
                    is String -> setString(key, value)
                    is Boolean -> setBoolean(key, value)
                    is Int -> setInt(key, value)
                    is Long -> setLong(key, value)
                    is Float -> setFloat(key, value)
                    else -> Log.w(TAG, "Unsupported config value type for key: $key")
                }
            }
            
            Log.d(TAG, "Remote configuration update completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating configuration from remote", e)
        }
    }
    
    /**
     * Export current configuration
     */
    fun exportConfiguration(): Map<String, Any> {
        val config = mutableMapOf<String, Any>()
        
        // Add all non-sensitive configuration
        regularPrefs.all.forEach { (key, value) ->
            if (value != null) {
                config[key] = value
            }
        }
        
        return config
    }
    
    /**
     * Reset configuration to defaults
     */
    suspend fun resetToDefaults() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Resetting configuration to defaults")
        
        try {
            regularPrefs.edit().clear().apply()
            
            // Set default values
            setApiTimeout(DEFAULT_API_TIMEOUT)
            setDownloadTimeout(DEFAULT_DOWNLOAD_TIMEOUT)
            setMaxCacheSizeMB(DEFAULT_MAX_CACHE_SIZE_MB)
            setAutoCleanupEnabled(true)
            setHardwareAccelerationEnabled(true)
            setPlaybackBufferMs(DEFAULT_PLAYBACK_BUFFER_MS)
            setHeartbeatIntervalSec(DEFAULT_HEARTBEAT_INTERVAL_SEC)
            setOfflineModeEnabled(true)
            setNetworkRetryAttempts(DEFAULT_NETWORK_RETRY_ATTEMPTS)
            setScreenBrightness(DEFAULT_SCREEN_BRIGHTNESS)
            setVolumeLevel(DEFAULT_VOLUME_LEVEL)
            setOrientationLock("auto")
            setPowerManagementEnabled(true)
            setLogLevel("INFO")
            
            Log.d(TAG, "Configuration reset to defaults completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting configuration", e)
        }
    }
    
    /**
     * Validate configuration values
     */
    fun validateConfiguration(): List<String> {
        val issues = mutableListOf<String>()
        
        // Validate timeout values
        if (getApiTimeout() < 5000) {
            issues.add("API timeout too low (minimum 5 seconds)")
        }
        
        if (getDownloadTimeout() < 10000) {
            issues.add("Download timeout too low (minimum 10 seconds)")
        }
        
        // Validate cache size
        if (getMaxCacheSizeMB() < 100) {
            issues.add("Cache size too small (minimum 100MB)")
        }
        
        // Validate buffer size
        if (getPlaybackBufferMs() < 1000) {
            issues.add("Playback buffer too small (minimum 1 second)")
        }
        
        // Validate heartbeat interval
        if (getHeartbeatIntervalSec() < 5) {
            issues.add("Heartbeat interval too frequent (minimum 5 seconds)")
        }
        
        return issues
    }
    
    // Helper methods for regular preferences
    private fun getString(key: String, defaultValue: String?): String? {
        return regularPrefs.getString(key, defaultValue)
    }
    
    private fun setString(key: String, value: String) {
        regularPrefs.edit().putString(key, value).apply()
    }
    
    private fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return regularPrefs.getBoolean(key, defaultValue)
    }
    
    private fun setBoolean(key: String, value: Boolean) {
        regularPrefs.edit().putBoolean(key, value).apply()
    }
    
    private fun getInt(key: String, defaultValue: Int): Int {
        return regularPrefs.getInt(key, defaultValue)
    }
    
    private fun setInt(key: String, value: Int) {
        regularPrefs.edit().putInt(key, value).apply()
    }
    
    private fun getLong(key: String, defaultValue: Long): Long {
        return regularPrefs.getLong(key, defaultValue)
    }
    
    private fun setLong(key: String, value: Long) {
        regularPrefs.edit().putLong(key, value).apply()
    }
    
    private fun getFloat(key: String, defaultValue: Float): Float {
        return regularPrefs.getFloat(key, defaultValue)
    }
    
    private fun setFloat(key: String, value: Float) {
        regularPrefs.edit().putFloat(key, value).apply()
    }
    
    // Helper methods for encrypted preferences
    private fun getSecureString(key: String, defaultValue: String? = null): String? {
        return encryptedPrefs?.getString(key, defaultValue) ?: defaultValue
    }
    
    private fun setSecureString(key: String, value: String) {
        encryptedPrefs?.edit()?.putString(key, value)?.apply()
    }
    
    private fun getSecureBoolean(key: String, defaultValue: Boolean): Boolean {
        return encryptedPrefs?.getBoolean(key, defaultValue) ?: defaultValue
    }
    
    private fun setSecureBoolean(key: String, value: Boolean) {
        encryptedPrefs?.edit()?.putBoolean(key, value)?.apply()
    }
}