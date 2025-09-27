package com.example.pixelflowplayer

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.example.pixelflowplayer.download.DownloadManager
import com.example.pixelflowplayer.download.DownloadPriority
import com.example.pixelflowplayer.download.DownloadProgress
import com.example.pixelflowplayer.download.DownloadRequest
import com.example.pixelflowplayer.network.NetworkManager
import com.example.pixelflowplayer.player.ApiClient
import com.example.pixelflowplayer.player.DeviceInfo
import com.example.pixelflowplayer.player.HeartbeatRequest
import com.example.pixelflowplayer.player.Playlist
import com.example.pixelflowplayer.player.PlaylistItem
import com.example.pixelflowplayer.player.DisplayInfo
import com.example.pixelflowplayer.player.DisplayModeInfo
import com.example.pixelflowplayer.player.VideoCodecSupport
import com.example.pixelflowplayer.player.StorageInfo
import com.example.pixelflowplayer.player.MemoryInfoDto
import com.example.pixelflowplayer.player.BatteryInfo
import com.example.pixelflowplayer.player.NetworkInfoDto
import com.example.pixelflowplayer.player.LocaleTimeInfo
import com.example.pixelflowplayer.player.PlayerStats
import com.example.pixelflowplayer.storage.StorageManager
import com.example.pixelflowplayer.network.WebSocketManager
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import com.google.gson.Gson
import android.view.Surface
import android.hardware.display.DisplayManager
import android.view.Display
import android.media.MediaCodecList
import android.media.AudioManager

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    // --- Views (restored from old file) ---
    private lateinit var rootLayout: FrameLayout
    private lateinit var pairingView: View
    private lateinit var loadingBar: ProgressBar
    private lateinit var mainStatusText: TextView
    private lateinit var urlText: TextView
    private lateinit var instructionsText: TextView
    private lateinit var exitButton: ImageView
    private lateinit var playerContainer: FrameLayout
    private lateinit var offlineIndicator: ImageView

    // --- Download Progress Views (restored from old file) ---
    private lateinit var downloadProgressView: LinearLayout
    private lateinit var downloadMainStatusText: TextView
    private lateinit var downloadOverallProgressBar: ProgressBar
    private lateinit var downloadProgressPercentageText: TextView
    private lateinit var downloadItemsCountText: TextView
    private lateinit var downloadErrorDetailsText: TextView

    // --- PlayerManager Integration (keeping modern approach) ---
    private lateinit var playerView: PlayerView
    private lateinit var imagePlayerView: ImageView
    private lateinit var statusText: TextView
    private var exoPlayer: ExoPlayer? = null
    private var imageAdvanceRunnable: Runnable? = null

    private lateinit var storageManager: StorageManager
    private lateinit var downloadManager: DownloadManager
    private lateinit var networkManager: NetworkManager

    // --- Device Management (restored from old file) ---
    private lateinit var deviceId: String
    private lateinit var connectivityManager: ConnectivityManager
    private var isNetworkCurrentlyConnected = false
    private val gson = Gson()

    // Schedule and playlist management (enhanced from both files)
    private val handler = Handler(Looper.getMainLooper())
    private var heartbeatJob: Job? = null
    private var flickerJob: Job? = null

    // Current playlist state
    private var currentPlaylistItems: List<PlaylistItem> = emptyList()
    private var currentItemIndex = 0
    private var contentRotation = 0
    private var displayMode: String? = "contain"

    // Last remote playlist for tracking changes
    private var lastRemotePlaylist: Playlist? = null

    // Guard and job for download-complete handoff to playback
    private var downloadProgressJob: Job? = null
    private var playbackStartedFromDownloads = false
    private var isBlockingOnDownload: Boolean = false

    // Transient controls (exit button) visibility handler
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { exitButton.visibility = View.GONE }

    // Prevent starting a new update while a previous update is in progress
    private var playlistUpdateInProgress = false

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_PENDING_PAIRING_CODE = "pendingPairingCode"
        private const val BASE_URL = "http://192.168.1.151:3000"
    }

    // Track once-only skip notifications per filename
    private val shownSkipToastKeys = mutableSetOf<String>()
    private fun filenameKey(urlOrPath: String): String {
        val base = urlOrPath.substringAfterLast('/')
        val win = base.substringAfterLast('\\')
        val cleaned = win.replace(Regex("^[0-9a-fA-F]{32}_"), "")
        return cleaned.lowercase(Locale.getDefault())
    }
    private fun toastOnceFor(urlOrPath: String, msg: String) {
        val key = filenameKey(urlOrPath)
        val show = synchronized(shownSkipToastKeys) { shownSkipToastKeys.add(key) }
        if (show) runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    private var wsManager: WebSocketManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        
        // 🔧 Configuration Debug Log
        Log.i(TAG, "🚀 PixelFlow Player Starting")
        Log.i(TAG, "🌐 Server URL: $BASE_URL")
        Log.i(TAG, "📱 Device Model: ${Build.MODEL}")
        Log.i(TAG, "🤖 Android Version: ${Build.VERSION.RELEASE}")

        Log.d(TAG, getString(R.string.log_start_enhanced_integration))

        // Initialize SharedPreferences and device management (restored from old file)
        // val sharedPreferences = getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE) // Unused
        deviceId = getOrCreateDeviceId()

        // --- WebSocket connect & register ---
        try {
            val uri = URI(BASE_URL)
            val host = uri.host ?: "192.168.1.151"
            val port = if (uri.port > 0) uri.port else 3000
            wsManager = WebSocketManager(host, port, deviceId)
            wsManager?.connect()
            val registerInfo = mapOf(
                "deviceId" to deviceId,
                "deviceInfo" to mapOf(
                    "model" to Build.MANUFACTURER + " " + Build.MODEL,
                    "androidVersion" to (Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()),
                    "appVersion" to getAppVersion(),
                    "display" to collectDisplayInfo(),
                    "videoSupport" to (collectVideoSupport() ?: emptyList())
                )
            )
            wsManager?.setRegisterPayload(registerInfo)
            wsManager?.sendJson("register", registerInfo)

            // NEW: command handler
            wsManager?.setCommandHandler { id, command, params ->
                handleRemoteCommand(id, command, params)
            }
        } catch (_: Exception) {}

        // Initialize views (restored from old file)
        findViews()
        exitButton.visibility = View.GONE // controls are transient; shown on user interaction
        exitButton.bringToFront()
        setupFullscreen()
        setupInteractions()

        // Initialize managers (keeping modern approach)
        storageManager = StorageManager(this)
        networkManager = NetworkManager(this)
        downloadManager = DownloadManager(this, storageManager, networkManager)

        Log.d(TAG, getString(R.string.log_setup_exoplayer))
        initializePlayer()

        // Initialize network monitoring (restored from old file)
        setupNetworkMonitoring()
        isNetworkCurrentlyConnected = isInitialNetworkConnected()

        // Start with splash screen flow (restored from old file)
        startSplashScreenFlow()
        startPeriodicNetworkCheck()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed, forcing a re-layout and re-render.")
        // Post to handler to ensure it runs after the configuration change is fully processed
        handler.post {
            playerContainer.requestLayout() // Explicitly request a re-measure and re-layout
            if (currentPlaylistItems.isNotEmpty()) {
                playCurrentItem()
            }
        }
    }

    private fun findViews() {
        rootLayout = findViewById(R.id.root_layout)
        pairingView = findViewById(R.id.pairing_view)
        loadingBar = findViewById(R.id.loading_bar)
        mainStatusText = findViewById(R.id.main_status_text)
        urlText = findViewById(R.id.url_text)
        instructionsText = findViewById(R.id.instructions_text)
        exitButton = findViewById(R.id.exit_button)
        playerContainer = findViewById(R.id.player_container)
        offlineIndicator = findViewById(R.id.offline_indicator)

        // PlayerManager views
        playerView = findViewById(R.id.video_player_view)
        imagePlayerView = findViewById(R.id.image_player_view)
        statusText = mainStatusText // Use same as mainStatusText

        // Download Progress Views
        downloadProgressView = findViewById(R.id.download_progress_view)
        downloadMainStatusText = findViewById(R.id.download_main_status_text)
        downloadOverallProgressBar = findViewById(R.id.download_overall_progress_bar)
        downloadProgressPercentageText = findViewById(R.id.download_progress_percentage_text)
        downloadItemsCountText = findViewById(R.id.download_items_count_text)
        downloadErrorDetailsText = findViewById(R.id.download_error_details_text)
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private fun startPeriodicNetworkCheck() {
        lifecycleScope.launch {
            while (isActive) {
                val currentNetworkStatus = isInitialNetworkConnected()
                if (currentNetworkStatus != isNetworkCurrentlyConnected) {
                    isNetworkCurrentlyConnected = currentNetworkStatus
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "Network state changed to: $isNetworkCurrentlyConnected. Updating UI.")
                        updateUI()
                    }
                }
                delay(15000)
            }
        }
    }

    private fun updateUI() {
        if (downloadProgressView.isVisible && !playerContainer.isVisible) return

        val currentlyPaired = getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).getBoolean("isPaired", false)
        flickerJob?.cancel()
        if (isNetworkCurrentlyConnected) {
            offlineIndicator.visibility = View.GONE
        } else {
            flickerJob = lifecycleScope.launch {
                while (isActive) {
                    withContext(Dispatchers.Main) {
                        offlineIndicator.visibility = if (offlineIndicator.isVisible) View.INVISIBLE else View.VISIBLE
                    }
                    delay(10000)
                }
            }
        }

        if (currentlyPaired) {
            val localPlaylist = getLocalPlaylist()
            if (localPlaylist != null && localPlaylist.items.isNotEmpty()) {
                startPlayback(localPlaylist)
            } else {
                showPairedScreen()
            }
            if (isNetworkCurrentlyConnected) startHeartbeat() else stopHeartbeat()
        } else {
            startHeartbeat()
        }
    }

    private fun startSplashScreenFlow() {
        lifecycleScope.launch {
            showLoadingScreen()
            delay(5000)
            withContext(Dispatchers.Main) {
                updateUI()
            }
        }
    }

    private fun getOrCreatePendingPairingCode(): String {
        val sharedPreferences = getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE)
        var code = sharedPreferences.getString(KEY_PENDING_PAIRING_CODE, null)
        if (code == null) {
            code = generatePairingCode()
            sharedPreferences.edit { putString(KEY_PENDING_PAIRING_CODE, code) }
        }
        return code
    }

    private fun getOrCreateDeviceId(): String {
        val sharedPreferences = getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE)
        var id = sharedPreferences.getString("deviceId", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            sharedPreferences.edit { putString("deviceId", id) }
        }
        return id
    }

    private fun generatePairingCode(): String {
        return (1..6).map { (('A'..'Z') + ('0'..'9')).random() }.joinToString("")
    }

    private fun isInitialNetworkConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = lifecycleScope.launch {
            var pairingCodeForHeartbeat = ""
            if (!getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).getBoolean("isPaired", false)) {
                pairingCodeForHeartbeat = getOrCreatePendingPairingCode()
                withContext(Dispatchers.Main) {
                    if (!downloadProgressView.isVisible) {
                        showPairedScreen(pairingCodeForHeartbeat, getString(R.string.pairing_instructions_unpaired))
                    }
                }
            }
            while (isActive) {
                if (!isNetworkCurrentlyConnected) {
                    delay(15000); continue
                }
                try {
                    // Create device info for heartbeat with display/codec support
                    val deviceInfo = DeviceInfo(
                        model = "${Build.MANUFACTURER} ${Build.MODEL}",
                        androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
                        appVersion = getAppVersion(),
                        display = collectDisplayInfo(),
                        videoSupport = collectVideoSupport(),
                        storage = collectStorageInfo(),
                        memory = collectMemoryInfo(),
                        battery = collectBatteryInfo(),
                        network = collectNetworkInfo(),
                        localeTime = collectLocaleTimeInfo(),
                        playerStats = collectPlayerStats()
                    )
                    val request = HeartbeatRequest(deviceId, pairingCodeForHeartbeat, deviceInfo)
                    val response = ApiClient.apiService.deviceHeartbeat(request)
                    if (response.isSuccessful) {
                        val heartbeatResponse = response.body()
                        val wasPaired = getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).getBoolean("isPaired", false)
                        Log.d(TAG, "🔄 Heartbeat Success. Status: ${heartbeatResponse?.status}. Paired: $wasPaired")
                        Log.d(TAG, "🔄 Playlist items count: ${heartbeatResponse?.playlist?.items?.size ?: 0}")
                        heartbeatResponse?.playlist?.items?.forEach { item ->
                            Log.d(TAG, "🔄 Item: ${item.type} - ${item.url} (${item.duration}s)")
                        }

                        if (wasPaired && heartbeatResponse?.status == "unpaired") {
                            handleUnpairingAndReset(); return@launch
                        }
                        when (heartbeatResponse?.status) {
                            "unpaired" -> {
                                // Ensure we have a pairing code and show pairing UI
                                if (pairingCodeForHeartbeat.isBlank()) {
                                    pairingCodeForHeartbeat = getOrCreatePendingPairingCode()
                                }
                                getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).edit {
                                    putBoolean("isPaired", false)
                                }
                                withContext(Dispatchers.Main) {
                                    // Stop any playback/overlays and show the pairing code screen
                                    downloadProgressJob?.cancel(); downloadProgressJob = null
                                    isBlockingOnDownload = false
                                    stopVideoPlayback()
                                    hideDownloadOverlay()
                                    currentPlaylistItems = emptyList()
                                    currentItemIndex = 0
                                    showPairedScreen(pairingCodeForHeartbeat, getString(R.string.pairing_instructions_unpaired))
                                }
                            }
                            "paired_waiting" -> {
                                if (!wasPaired) {
                                    getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).edit {
                                        putBoolean("isPaired", true); remove(
                                        KEY_PENDING_PAIRING_CODE
                                    )
                                    }
                                    pairingCodeForHeartbeat = ""
                                }
                                // New behavior: playlist was removed in CMS → stop playback and clear local cache
                                Log.d(TAG, getString(R.string.log_paired_waiting))
                                // cancel any ongoing download monitoring
                                downloadProgressJob?.cancel(); downloadProgressJob = null
                                playbackStartedFromDownloads = false
                                playlistUpdateInProgress = false
                                withContext(Dispatchers.Main) {
                                    if (downloadProgressView.isVisible) downloadProgressView.visibility = View.GONE
                                    // Stop playback and reset in-memory list
                                    stopVideoPlayback()
                                    currentPlaylistItems = emptyList()
                                    currentItemIndex = 0
                                    // Remove saved local playlist
                                    getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).edit { remove("localPlaylist") }
                                }
                                // Clear cached media files
                                withContext(Dispatchers.IO) {
                                    try {
                                        storageManager.clearCache()
                                    } catch (_: Exception) {
                                    }
                                }
                                withContext(Dispatchers.Main) { showPairedScreen() }
                            }
                            "playing" -> {
                                if (!getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).getBoolean("isPaired", false)) {
                                    getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).edit {
                                        putBoolean("isPaired", true); remove(
                                        KEY_PENDING_PAIRING_CODE
                                    )
                                    }
                                    pairingCodeForHeartbeat = ""
                                }

                                val newPlaylistFromServer = heartbeatResponse.playlist

                                // Ensure the paired screen is hidden as soon as content is assigned
                                withContext(Dispatchers.Main) {
                                    // Always force player visible on PLAYING
                                    pairingView.visibility = View.GONE
                                    isBlockingOnDownload = false
                                    hideDownloadOverlay()
                                    playerContainer.visibility = View.VISIBLE
                                    playerContainer.bringToFront()
                                }

                                if (newPlaylistFromServer != null) {
                                    withContext(Dispatchers.Main) { applyPlaylistOrientation(newPlaylistFromServer.orientation) }
                                    withContext(Dispatchers.Main) { applyContentRotation(heartbeatResponse.rotation ?: 0) }
                                }

                                // If CMS assigned an empty playlist, go back to paired/waiting screen
                                if (newPlaylistFromServer != null && newPlaylistFromServer.items.isEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        stopVideoPlayback()
                                        currentPlaylistItems = emptyList()
                                        currentItemIndex = 0
                                        getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).edit { remove("localPlaylist") }
                                        showPairedScreen()
                                    }
                                }

                                if (newPlaylistFromServer != null && newPlaylistFromServer.items.isNotEmpty()) {
                                    val playlistContentHasChanged = lastRemotePlaylist?.isContentEqualTo(newPlaylistFromServer) != true
                                    if (playlistContentHasChanged && !playlistUpdateInProgress) {
                                        Log.d(TAG, getString(R.string.log_new_playlist_detected))
                                        playlistUpdateInProgress = true
                                        withContext(Dispatchers.Main) {
                                            downloadAndPlayPlaylist(newPlaylistFromServer)
                                        }
                                    } else if (!playlistContentHasChanged) {
                                        val localPlaylist = getLocalPlaylist()
                                        val playable = localPlaylist?.let { buildLocalPlaylistFromCache(it) }
                                        val hasPlayable = playable != null && playable.items.isNotEmpty()

                                        if (currentPlaylistItems.isEmpty() || !hasPlayable) {
                                            Log.d(TAG, getString(R.string.log_player_idle))
                                            // Force download/start because cache was likely cleared after paired_waiting
                                            withContext(Dispatchers.Main) {
                                                downloadAndPlayPlaylist(newPlaylistFromServer, force = true)
                                            }
                                        } else if (currentPlaylistItems.isEmpty() && hasPlayable) {
                                            withContext(Dispatchers.Main) { startPlayback(playable!!) }
                                        }
                                    }
                                } else if (heartbeatResponse.playlist == null) {
                                    // Playlist was removed on server, stop playback
                                    withContext(Dispatchers.Main) {
                                        if (downloadProgressView.isVisible) downloadProgressView.visibility = View.GONE
                                        showPairedScreen()
                                    }
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "Heartbeat Error: ${response.code()}")
                        // If the device was deleted from CMS, backend may respond 404/410.
                        // Force a reset to pairing state so a new PIN is shown.
                        if (response.code() == 404 || response.code() == 410) {
                            handleUnpairingAndReset(); return@launch
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Heartbeat Exception: ${e.message}")
                }
                delay(15000)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun handleUnpairingAndReset() {
        stopHeartbeat()
        stopVideoPlayback()
        currentPlaylistItems = emptyList()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    storageManager.clearCache()
                } catch (_: Exception) {
                }
            }
            val newPairingCode = generatePairingCode()
            getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).edit(commit = true) {
                putBoolean("isPaired", false)
                remove("localPlaylist")
                putString(KEY_PENDING_PAIRING_CODE, newPairingCode)
            }
            try {
                val deviceInfo = DeviceInfo(
                    model = "${Build.MANUFACTURER} ${Build.MODEL}",
                    androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
                    appVersion = getAppVersion(),
                    display = collectDisplayInfo(),
                    videoSupport = collectVideoSupport(),
                    storage = collectStorageInfo(),
                    memory = collectMemoryInfo(),
                    battery = collectBatteryInfo(),
                    network = collectNetworkInfo(),
                    localeTime = collectLocaleTimeInfo(),
                    playerStats = collectPlayerStats()
                )
                ApiClient.apiService.deviceHeartbeat(HeartbeatRequest(deviceId, newPairingCode, deviceInfo))
            } catch (e: Exception) {
                Log.e(TAG, getString(R.string.log_failed_heartbeat_on_reset), e)
            }
            withContext(Dispatchers.Main) {
                downloadProgressView.visibility = View.GONE
                updateUI()
            }
        }
    }

    private fun saveLocalPlaylist(playlist: Playlist) {
        val jsonString = gson.toJson(playlist)
        getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).edit { putString("localPlaylist", jsonString) }
    }

    private fun getLocalPlaylist(): Playlist? {
        val jsonString = getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).getString("localPlaylist", null) ?: return null
        return try {
            gson.fromJson(jsonString, Playlist::class.java)
        } catch (e: Exception) {
            Log.e(TAG, getString(R.string.log_error_parsing_playlist), e)
            null
        }
    }

    // Build a local playlist that rewrites URLs to file:// cached paths, excluding any items not cached
    private fun buildLocalPlaylistFromCache(source: Playlist): Playlist {
        Log.d(TAG, "🔍 Building local playlist from cache. Source has ${source.items.size} items")
        val mapped = source.items.mapNotNull { item ->
            val remoteUrl = if (item.url.startsWith("http") || item.url.startsWith("file://")) item.url else "$BASE_URL${item.url}"
            val file = storageManager.getCacheFile(remoteUrl)
            Log.d(TAG, "🔍 Checking item: ${item.url} -> $remoteUrl")
            Log.d(TAG, "🔍 Cache file: ${file.absolutePath} (exists: ${file.exists()}, size: ${if (file.exists()) file.length() else 0})")
            if (file.exists() && file.length() > 0) {
                Log.d(TAG, "✅ Found cached file for: $remoteUrl")
                item.copy(url = "file://${file.absolutePath}")
            } else {
                Log.w(TAG, "❌ No cached file for: $remoteUrl")
                null
            }
        }
        Log.d(TAG, "🔍 Built local playlist with ${mapped.size} cached items")
        return Playlist(items = mapped, orientation = source.orientation, transitionType = source.transitionType)
    }

    /**
     * 📥 Download all playlist items first, then start playback (enhanced for Playlist object)
     */
    private fun downloadAndPlayPlaylist(newPlaylist: Playlist, force: Boolean = false) {
        if (!force && lastRemotePlaylist?.isContentEqualTo(newPlaylist) == true) {
            Log.d(TAG, getString(R.string.log_playlist_unchanged))
            playlistUpdateInProgress = false
            return
        }
        lastRemotePlaylist = newPlaylist

        playlistUpdateInProgress = true

        if (newPlaylist.items.isEmpty()) {
            Log.w(TAG, getString(R.string.log_no_items_to_download))
            statusText.text = getString(R.string.status_no_items_to_play)
            showPairedScreen()
            playlistUpdateInProgress = false
            return
        }

        val allTargetUrls: Set<String> = newPlaylist.items.map { item ->
            if (item.url.startsWith("http")) item.url else "$BASE_URL${item.url}"
        }.toSet()

        lifecycleScope.launch(Dispatchers.IO) {
            val missingUrls = allTargetUrls.filter { url ->
                val f = storageManager.getCacheFile(url)
                !f.exists() || f.length() == 0L
            }.toSet()

            // If nothing is missing, do not show the blocking download UI. Just rebuild and play.
            if (missingUrls.isEmpty()) {
                Log.d(TAG, "No downloads needed; applying metadata changes and starting playback immediately.")
                storageManager.cleanupCacheExcept(allTargetUrls)
                val localPl = buildLocalPlaylistFromCache(newPlaylist)
                withContext(Dispatchers.Main) {
                    isBlockingOnDownload = false
                    downloadProgressJob?.cancel(); downloadProgressJob = null
                    saveLocalPlaylist(newPlaylist)
                    if (localPl.items.isNotEmpty()) startPlayback(localPl) else showPairedScreen(instructions = getString(R.string.status_no_items_to_play))
                    playlistUpdateInProgress = false
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                // Show blocking overlay only when we really need to download files
                isBlockingOnDownload = true
                stopVideoPlayback()
                playerContainer.visibility = View.GONE
                showDownloadOverlay(allTargetUrls.size)

                saveLocalPlaylist(newPlaylist)
                playbackStartedFromDownloads = false
                downloadProgressJob?.cancel()
                monitorDownloadProgress(allTargetUrls)

                // Launch downloads only for the missing ones
                queueDownloads(newPlaylist, missingUrls, allTargetUrls)
            }
        }
    }

    private fun handleAllItemsCached(playlist: Playlist, urls: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            storageManager.cleanupCacheExcept(urls)
        }
        val localPl = buildLocalPlaylistFromCache(playlist)
        saveLocalPlaylist(localPl)
        if (localPl.items.isNotEmpty()) {
            Log.d(TAG, getString(R.string.log_media_cached))
            startPlayback(localPl)
        } else {
            Log.e(TAG, "All items were cached, but local playlist is empty. This shouldn't happen.")
            showPairedScreen(instructions = "Error: Could not prepare content.")
        }
        playlistUpdateInProgress = false
    }

    private fun queueDownloads(playlist: Playlist, missingUrls: Set<String>, allUrls: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                playlist.items.forEach { item ->
                    val fullUrl = if (item.url.startsWith("http")) item.url else "$BASE_URL${item.url}"
                    if (missingUrls.contains(fullUrl)) {
                        val request = DownloadRequest(
                            url = fullUrl,
                            priority = DownloadPriority.HIGH,
                            metadata = mapOf("type" to item.type, "duration" to item.duration.toString())
                        )
                        downloadManager.queueDownload(request)
                    }
                }
                storageManager.cleanupCacheExcept(allUrls)
            } catch (e: Exception) {
                Log.e(TAG, "Error queueing downloads", e)
                withContext(Dispatchers.Main) {
                    if (playerContainer.isVisible) {
                        Log.w(TAG, "Continuing with current playlist after download error.")
                    } else {
                        showPairedScreen(instructions = "Failed to download content.")
                    }
                    playlistUpdateInProgress = false
                }
            }
        }
    }

    /**
     * 🔄 Monitor download progress and start playback when ready
     */
    private fun monitorDownloadProgress(urlsToTrack: Set<String>) {
        downloadProgressJob?.cancel()
        downloadProgressJob = CoroutineScope(Dispatchers.IO).launch {
            val errors = mutableListOf<String>()
            downloadManager.downloadProgress
                .collect { progressMap ->
                    val completedCount = urlsToTrack.count { url ->
                        val file = storageManager.getCacheFile(url)
                        file.exists() && file.length() > 0 || progressMap[url] is DownloadProgress.Completed
                    }
                    val failedCount = urlsToTrack.count { url -> progressMap[url] is DownloadProgress.Failed }

                    val percent = if (urlsToTrack.isNotEmpty()) (completedCount * 100) / urlsToTrack.size else 100
                    val currentName = progressMap.entries.firstOrNull { it.value is DownloadProgress.InProgress }?.key?.substringAfterLast('/') ?: ""

                    // NEW: emit realtime download progress
                    try {
                        wsManager?.sendJson("download_progress", mapOf(
                            "completed" to completedCount,
                            "failed" to failedCount,
                            "total" to urlsToTrack.size,
                            "percent" to percent,
                            "currentName" to currentName
                        ))
                    } catch (_: Exception) {}

                    withContext(Dispatchers.Main) {
                        updateDownloadOverlay(completedCount, urlsToTrack.size, percent, currentName, errors)
                    }

                    // When all items finished (completed + failed), start playback with cached-only items
                    if (completedCount + failedCount >= urlsToTrack.size) {
                        Log.d(TAG, "Downloads finished. Completed=$completedCount, Failed=$failedCount. Starting with cached items only.")
                        // Give the filesystem a moment to flush writes before we scan cache
                        delay(350)
                        val freshPlaylist = (lastRemotePlaylist ?: getLocalPlaylist())?.let { buildLocalPlaylistFromCache(it) }
                        Log.d(TAG, "🔍 freshPlaylist items after cache scan: ${freshPlaylist?.items?.size ?: 0}")
                        withContext(Dispatchers.Main) {
                            isBlockingOnDownload = false
                            hideDownloadOverlay()
                            if (freshPlaylist != null && freshPlaylist.items.isNotEmpty()) {
                                startPlayback(freshPlaylist)
                            } else {
                                // Nothing cached → show paired/waiting
                                showPairedScreen(instructions = getString(R.string.status_no_items_to_play))
                            }
                        }
                        playlistUpdateInProgress = false
                        downloadProgressJob?.cancel()
                    }
                }
        }
    }

    /**
     * Start playback for the given playlist using direct ExoPlayer/Glide pipeline
     */
    private fun startPlayback(playlist: Playlist) {
        Log.d(TAG, "🎬 startPlayback() with ${playlist.items.size} items")
        runOnUiThread {
            // Belt-and-suspenders: whenever playback starts, ensure overlay is gone and player is visible
            pairingView.visibility = View.GONE
            isBlockingOnDownload = false
            hideDownloadOverlay()
            playerContainer.visibility = View.VISIBLE
            playerContainer.bringToFront()
            applyPlaylistOrientation(playlist.orientation)
            if (playerContainer.visibility != View.VISIBLE) showPlayerScreen()

            currentPlaylistItems = playlist.items
            currentItemIndex = 0
            if (currentPlaylistItems.isNotEmpty()) {
                playCurrentItem()
            } else {
                statusText.text = getString(R.string.status_no_items_to_play)
                showPairedScreen()
            }
        }
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, rootLayout)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupInteractions() {
        // Exit button: confirmation dialog
        exitButton.isClickable = true
        exitButton.setOnClickListener { showExitConfirmDialog() }

        // Show transient controls on any user interaction
        val touchListener = View.OnTouchListener { _, _ ->
            showTransientControls(); false
        }
        rootLayout.setOnTouchListener(touchListener)
        playerView.setOnTouchListener(touchListener)
        imagePlayerView.setOnTouchListener(touchListener)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        showTransientControls()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) showTransientControls()
        return super.dispatchKeyEvent(event)
    }

    private fun showTransientControls() {
        exitButton.visibility = View.VISIBLE
        exitButton.bringToFront()
        controlsHandler.removeCallbacks(hideControlsRunnable)
        controlsHandler.postDelayed(hideControlsRunnable, 10_000)
    }

    private fun showExitConfirmDialog() {
        showTransientControls()
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.exit_confirm_message))
            .setPositiveButton(getString(R.string.action_yes)) { _, _ ->
                try { stopLockTask() } catch (_: Exception) {}
                try { exoPlayer?.release() } catch (_: Exception) {}
                finishAndRemoveTask(); finishAffinity()
            }
            .setNegativeButton(getString(R.string.action_no)) { dialog, _ ->
                dialog.dismiss()
                showTransientControls()
            }
            .setCancelable(true)
            .show()
    }

    private fun showPairedScreen(pairingCode: String? = null, instructions: String? = null) {
        pairingView.visibility = View.VISIBLE
        playerContainer.visibility = View.GONE
        downloadProgressView.visibility = View.GONE
        loadingBar.visibility = View.GONE
        mainStatusText.text = pairingCode ?: getString(R.string.paired_screen_paired)
        instructionsText.text = instructions ?: getString(R.string.paired_screen_waiting_for_content)
        exitButton.bringToFront()
        // keep controls transient; don't force visible here
        stopLockTask()
    }

    private fun showLoadingScreen() {
        pairingView.visibility = View.GONE
        playerContainer.visibility = View.GONE
        downloadProgressView.visibility = View.GONE
        loadingBar.visibility = View.VISIBLE
        mainStatusText.text = getString(R.string.loading_screen_loading)
        exitButton.bringToFront()
    }

    private fun showDownloadOverlay(
        missing: Int
    ) {
        // Hide other UI layers while we block for downloads
        pairingView.visibility = View.GONE
        playerContainer.visibility = View.GONE
        downloadProgressView.visibility = View.VISIBLE
        downloadMainStatusText.text = getString(R.string.download_overlay_downloading)
        downloadOverallProgressBar.progress = 0
        downloadProgressPercentageText.text = "0%"
        downloadItemsCountText.text = "0 / $missing"
        downloadErrorDetailsText.text = ""
        exitButton.bringToFront()
    }

    private fun hideDownloadOverlay() {
        downloadProgressView.visibility = View.GONE
    }

    @SuppressLint("SetTextI18n")
    private fun updateDownloadOverlay(
        completed: Int,
        total: Int,
        percent: Int,
        currentItemName: String,
        errors: List<String>
    ) {
        if (downloadProgressView.isVisible) {
            val msg = if (currentItemName.isNotBlank())
                getString(R.string.download_overlay_downloading_item, currentItemName)
            else
                getString(R.string.download_overlay_downloading)
            downloadMainStatusText.text = msg
            downloadOverallProgressBar.progress = percent
            downloadProgressPercentageText.text = "$percent%"
            downloadItemsCountText.text = "$completed / $total"
            if (errors.isNotEmpty()) {
                downloadErrorDetailsText.visibility = View.VISIBLE
                downloadErrorDetailsText.text = errors.joinToString("\n")
            } else {
                downloadErrorDetailsText.visibility = View.GONE
            }
        }
    }

    private fun advanceToNextItem() {
        if (currentPlaylistItems.isEmpty()) return
        currentItemIndex = (currentItemIndex + 1) % currentPlaylistItems.size
        playCurrentItem()
    }

    // Play the current item (image or video) from the cached file:// URL
    private fun playCurrentItem() {
        if (currentPlaylistItems.isEmpty()) {
            Log.w(TAG, getString(R.string.log_no_items_to_play))
            statusText.text = getString(R.string.status_no_items_to_play)
            showPairedScreen()
            return
        }

        // Ensure UI state
        handler.removeCallbacksAndMessages(null)
        loadingBar.visibility = View.GONE
        if (!isBlockingOnDownload) hideDownloadOverlay()
        pairingView.visibility = View.GONE
        playerContainer.visibility = View.VISIBLE
        playerContainer.bringToFront()

        val item = currentPlaylistItems[currentItemIndex]
        Log.d(TAG, "🎬 Playing item ${currentItemIndex + 1}/${currentPlaylistItems.size}: ${item.url}")

        // QUICK GUARD: ensure cached file exists before trying to play
        if (item.url.startsWith("file://")) {
            try {
                val path = item.url.removePrefix("file://")
                val f = File(path)
                if (!f.exists() || f.length() == 0L) {
                    Log.w(TAG, "Cached file missing/empty, skipping: $path")
                    // Attempt to requeue original download by filename
                    val cachedKey = filenameKey(item.url)
                    val remote = lastRemotePlaylist?.items?.firstOrNull { filenameKey(it.url) == cachedKey }?.let {
                        if (it.url.startsWith("http")) it.url else "$BASE_URL${it.url}"
                    }
                    if (remote != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val req = DownloadRequest(url = remote, priority = DownloadPriority.HIGH, metadata = mapOf("type" to item.type))
                                downloadManager.queueDownload(req)
                            } catch (_: Exception) {}
                        }
                    }
                    toastOnceFor(item.url, "Skipped missing file. Re-downloading…")
                    handler.post { advanceToNextItem() }
                    return
                }
            } catch (_: Exception) { /* ignore */ }
        }

        // Emit now_playing
        try {
            wsManager?.sendJson("now_playing", mapOf(
                "index" to currentItemIndex,
                // Use mediaType to avoid overriding the event type in WS
                "mediaType" to item.type,
                "url" to item.url,
                "displayMode" to (item.displayMode ?: "contain"),
                "rotation" to contentRotation,
                "duration" to item.duration
            ))
        } catch (_: Exception) {}

        // Only play local cached items
        if (!item.url.startsWith("file://")) {
            Log.w(TAG, "Skipping non-local item: ${item.url}")
            handler.post { advanceToNextItem() }
            return
        }

        // Apply display mode for images
        fun applyImageDisplayMode(mode: String?) {
            when ((mode ?: "contain").lowercase()) {
                "cover" -> imagePlayerView.scaleType = ImageView.ScaleType.CENTER_CROP
                "fill" -> imagePlayerView.scaleType = ImageView.ScaleType.FIT_XY
                else -> imagePlayerView.scaleType = ImageView.ScaleType.FIT_CENTER
            }
        }

        // Apply display mode for videos
        fun applyVideoDisplayMode(mode: String?) {
            when ((mode ?: "contain").lowercase()) {
                // Contain: fit inside without cropping
                "contain" -> {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    exoPlayer?.setVideoScalingMode(C.VIDEO_SCALING_MODE_DEFAULT)
                }
                // Cover: fill and crop overflow
                "cover" -> {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    exoPlayer?.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                }
                // Fill: stretch to view bounds (may distort)
                "fill" -> {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                    exoPlayer?.setVideoScalingMode(C.VIDEO_SCALING_MODE_DEFAULT)
                }
                else -> {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    exoPlayer?.setVideoScalingMode(C.VIDEO_SCALING_MODE_DEFAULT)
                }
            }
        }

        // Stop any pending image advance
        imageAdvanceRunnable?.let { handler.removeCallbacks(it) }
        imageAdvanceRunnable = null

        if (item.type.equals("video", ignoreCase = true)) {
            // Video via ExoPlayer
            imagePlayerView.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            // NEW: apply media fit for videos
            applyVideoDisplayMode(item.displayMode)
            try {
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                val mediaItem = MediaItem.fromUri(item.url)
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare()
                exoPlayer?.playWhenReady = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start video playback", e)
                handler.post { advanceToNextItem() }
            }
        } else {
            // Image via Glide
            playerView.visibility = View.GONE
            imagePlayerView.visibility = View.VISIBLE
            applyImageDisplayMode(item.displayMode)
            try {
                Glide.with(this).load(item.url).into(imagePlayerView)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load image", e)
                handler.post { advanceToNextItem() }
                return
            }
            val seconds = if (item.duration > 0) item.duration else 8
            imageAdvanceRunnable = Runnable { advanceToNextItem() }
            handler.postDelayed(imageAdvanceRunnable!!, seconds * 1000L)
        }
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // Ensure the player surface is visible when frames are ready
                    hideDownloadOverlay()
                    pairingView.visibility = View.GONE
                    playerContainer.visibility = View.VISIBLE
                    playerContainer.bringToFront()

                    // NEW: when a video is ready, report its duration (seconds) to CMS for countdown
                    try {
                        val current = currentPlaylistItems.getOrNull(currentItemIndex)
                        if (current != null && current.type.equals("video", ignoreCase = true)) {
                            val durMs = exoPlayer?.duration ?: -1L
                            if (durMs > 0) {
                                val id = "${currentItemIndex}-${current.url}"
                                wsManager?.sendJson("now_playing", mapOf(
                                    "index" to currentItemIndex,
                                    // include mediaType explicitly
                                    "mediaType" to current.type,
                                    "url" to current.url,
                                    "displayMode" to (current.displayMode ?: "contain"),
                                    "rotation" to contentRotation,
                                    "duration" to (durMs / 1000).toInt(),
                                    "id" to id
                                ))
                            }
                        }
                    } catch (_: Exception) {}
                }
                if (playbackState == Player.STATE_ENDED) {
                    advanceToNextItem()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    hideDownloadOverlay()
                    pairingView.visibility = View.GONE
                    playerContainer.visibility = View.VISIBLE
                    playerContainer.bringToFront()
                }
            }
            // NEW: handle playback errors by deleting bad cache, re-queuing download, and skipping forward
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Playback error", error)
                val badItem = currentPlaylistItems.getOrNull(currentItemIndex)
                if (badItem != null && badItem.url.startsWith("file://")) {
                    try {
                        val path = badItem.url.removePrefix("file://")
                        val f = File(path)
                        if (f.exists()) {
                            f.delete()
                            Log.w(TAG, "Deleted corrupt cached file: ${f.name}")
                        }
                    } catch (_: Exception) {}

                    // Try to find the remote URL by filename match (strip optional md5_ prefix)
                    val cachedKey = filenameKey(badItem.url)
                    val remote = lastRemotePlaylist?.items?.firstOrNull { filenameKey(it.url) == cachedKey }?.let {
                        if (it.url.startsWith("http")) it.url else "$BASE_URL${it.url}"
                    }
                    if (remote != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val req = DownloadRequest(url = remote, priority = DownloadPriority.HIGH, metadata = mapOf("type" to badItem.type))
                                downloadManager.queueDownload(req)
                            } catch (_: Exception) {}
                        }
                    }
                    toastOnceFor(badItem.url, "Skipped corrupt file. Re-downloading…")
                }
                // Skip to next item so UI doesn’t hang
                handler.post { advanceToNextItem() }
            }
        })
    }

    private fun stopVideoPlayback() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
    }

    private fun applyPlaylistOrientation(orientation: String?) {
        val normalized = orientation?.trim()?.lowercase()
        val newOrientation = when (normalized) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != newOrientation) {
            Log.d(TAG, "Requesting orientation change to: ${normalized ?: "unspecified"}")
            requestedOrientation = newOrientation
        }
    }

    // NEW: apply content rotation coming from backend (0/90/180/270)
    private fun applyContentRotation(rotationDegrees: Int) {
        val rot = ((rotationDegrees % 360) + 360) % 360 // normalize
        Log.d(TAG, "Applying content rotation: $rot°")
        // Remember the screen rotation coming from CMS so we can report it in now_playing
        contentRotation = rot
        // Rotate both the image and video containers
        imagePlayerView.rotation = rot.toFloat()
        playerView.rotation = rot.toFloat()
    }

    private fun showPlayerScreen() {
        pairingView.visibility = View.GONE
        playerContainer.visibility = View.VISIBLE
        playerContainer.bringToFront()
        exitButton.bringToFront()
        // Start kiosk only if permitted to avoid system education dialog overlaying the player
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isLockTaskPermitted(packageName)) {
                startLockTask()
            }
        } catch (_: Exception) { /* ignore on non-DO devices */ }
    }

    // Helper: app version without BuildConfig dependency
    private fun getAppVersion(): String {
        return try {
            val p = packageManager.getPackageInfo(packageName, 0)
            // Prefer versionName; fall back to longVersionCode
            @Suppress("DEPRECATION")
            p.versionName ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.longVersionCode.toString() else "1.0.0")
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    private fun collectStorageInfo(): StorageInfo? {
        return try {
            val dir = filesDir
            val stat = StatFs(dir.absolutePath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val cacheDir = File(filesDir, "media_cache")
            val cache = computeDirSize(cacheDir)
            StorageInfo(totalBytes = total, freeBytes = free, appCacheBytes = cache)
        } catch (_: Exception) { null }
    }

    private fun computeDirSize(file: File?): Long {
        if (file == null || !file.exists()) return 0
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { computeDirSize(it) } ?: 0
    }

    private fun collectMemoryInfo(): MemoryInfoDto? {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            MemoryInfoDto(totalRamBytes = mi.totalMem, availRamBytes = mi.availMem, lowMemory = mi.lowMemory)
        } catch (_: Exception) { null }
    }

    private fun collectBatteryInfo(): BatteryInfo? {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) ((level * 100f / scale).toInt()) else null
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            BatteryInfo(levelPercent = pct, charging = charging, powerSave = pm.isPowerSaveMode)
        } catch (_: Exception) { null }
    }

    private fun collectNetworkInfo(): NetworkInfoDto? {
        return try {
            val cm = connectivityManager
            val active = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(active)
            val online = caps != null
            val transports = mutableListOf<String>()
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) transports.add("WiFi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) transports.add("Cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) transports.add("Ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) transports.add("VPN")
            val metered = try { cm.isActiveNetworkMetered } catch (_: Exception) { null }
            val validated = try { caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } catch (_: Exception) { null }
            val vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            NetworkInfoDto(online, transports, metered, validated, vpnActive)
        } catch (_: Exception) { null }
    }

    private fun collectLocaleTimeInfo(): LocaleTimeInfo? {
        return try {
            val tz = TimeZone.getDefault().id
            val lc = Locale.getDefault().toLanguageTag()
            val is24 = android.text.format.DateFormat.is24HourFormat(this)
            val uptime = SystemClock.uptimeMillis()
            LocaleTimeInfo(timezone = tz, locale = lc, is24h = is24, uptimeMs = uptime)
        } catch (_: Exception) { null }
    }

    private fun md5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(input.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }

    private fun collectPlayerStats(): PlayerStats? {
        return try {
            val pl = lastRemotePlaylist
            val urls = pl?.items?.joinToString("|") { it.url }
            val hash = urls?.let { md5(it) }
            val idx = currentItemIndex
            val current = currentPlaylistItems.getOrNull(idx)
            val decoder = try { exoPlayer?.videoFormat?.codecs } catch (_: Exception) { null }
            PlayerStats(
                playlistHash = hash,
                itemCount = currentPlaylistItems.size,
                currentIndex = if (idx >= 0) idx else 0,
                currentUrl = current?.url,
                currentType = current?.type,
                decoderName = decoder,
                droppedFrames = null, // optional, can be wired with analytics later
                lastDownloadErrors = null
            )
        } catch (_: Exception) { null }
    }

    // Helper: collect current display metrics and supported modes
    private fun collectDisplayInfo(): DisplayInfo? {
        return try {
            val dm = resources.displayMetrics
            val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val refresh = try { disp?.refreshRate ?: 60f } catch (_: Exception) { 60f }
            val rotationDegrees = when (try { disp?.rotation } catch (_: Exception) { Surface.ROTATION_0 }) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val mgr = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val d = mgr.getDisplay(Display.DEFAULT_DISPLAY)
                d?.supportedModes?.map { mode: Display.Mode ->
                    DisplayModeInfo(
                        widthPx = mode.physicalWidth,
                        heightPx = mode.physicalHeight,
                        refreshRate = try { mode.refreshRate } catch (_: Exception) { d.refreshRate }
                    )
                }
            } else null
            DisplayInfo(
                currentWidthPx = dm.widthPixels,
                currentHeightPx = dm.heightPixels,
                densityDpi = dm.densityDpi,
                refreshRate = refresh,
                rotation = rotationDegrees,
                supportedModes = supported
            )
        } catch (_: Exception) {
            null
        }
    }

    // Helper: collect approximate decoder max resolution for common codecs
    private fun collectVideoSupport(): List<VideoCodecSupport>? {
        return try {
            val wanted = listOf("video/avc", "video/hevc")
            val results = mutableMapOf<String, Pair<Int, Int>>()
            val list = if (Build.VERSION.SDK_INT >= 21) MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos else @Suppress("DEPRECATION") MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            for (info in list) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.lowercase() in wanted) {
                        try {
                            val caps = info.getCapabilitiesForType(type)
                            val v = caps.videoCapabilities
                            val w = v.supportedWidths.upper
                            val h = v.supportedHeights.upper
                            val prev = results[type.lowercase()]
                            if (prev == null || (w * h) > (prev.first * prev.second)) {
                                results[type.lowercase()] = w to h
                            }
                        } catch (_: Exception) { /* ignore codec */ }
                    }
                }
            }
            results.map { (codec, wh) -> VideoCodecSupport(codec, wh.first, wh.second) }
        } catch (_: Exception) { null }
    }

    private fun handleRemoteCommand(id: String, command: String, params: Map<String, Any?>) {
        when (command) {
            "force_update", "reload_playlist" -> {
                runOnUiThread {
                    val pl = lastRemotePlaylist
                    if (pl != null) {
                        downloadAndPlayPlaylist(pl, force = true)
                        try { wsManager?.sendJson("command_result", mapOf("id" to id, "status" to "ok")) } catch (_: Exception) {}
                    } else {
                        try { wsManager?.sendJson("command_result", mapOf("id" to id, "status" to "error", "error" to "no_playlist")) } catch (_: Exception) {}
                    }
                }
            }
            "clear_cache" -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    try { storageManager.clearCache() } catch (_: Exception) {}
                    withContext(Dispatchers.Main) {
                        stopVideoPlayback()
                        currentPlaylistItems = emptyList()
                        currentItemIndex = 0
                        try { wsManager?.sendJson("command_result", mapOf("id" to id, "status" to "ok")) } catch (_: Exception) {}
                    }
                }
            }
            "set_volume" -> {
                try {
                    val level = (params["level"] as? Number)?.toInt() ?: 50
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val vol = (level * max / 100).coerceIn(0, max)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                    wsManager?.sendJson("command_result", mapOf("id" to id, "status" to "ok", "data" to mapOf("level" to level)))
                } catch (e: Exception) {
                    try { wsManager?.sendJson("command_result", mapOf("id" to id, "status" to "error", "error" to e.message)) } catch (_: Exception) {}
                }
            }
            
            "next_item" -> {
                runOnUiThread { advanceToNextItem() }
                try { wsManager?.sendJson("command_result", mapOf("id" to id, "status" to "ok")) } catch (_: Exception) {}
            }
            "set_rotation" -> {
                val deg = (params["degrees"] as? Number)?.toInt() ?: 0
                runOnUiThread { applyContentRotation(deg) }
                try { wsManager?.sendJson("command_result", mapOf("id" to id, "status" to "ok")) } catch (_: Exception) {}
            }
            "restart_app" -> {
                // Ack first, then schedule a safe restart
                try { wsManager?.sendJson("command_result", mapOf("id" to id, "status" to "ok")) } catch (_: Exception) {}
                runOnUiThread { scheduleAppRestart(600) }
            }
            else -> {
                try { wsManager?.sendJson("command_result", mapOf("id" to id, "status" to "error", "error" to "unsupported_command")) } catch (_: Exception) {}
            }
        }
    }

    private fun scheduleAppRestart(delayMs: Long = 500) {
        try {
            val ctx = applicationContext
            val intent = packageManager?.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                val pi = PendingIntent.getActivity(
                    ctx,
                    0,
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pi)
            }
        } catch (_: Exception) { }
        // Try to close gracefully
        try { exoPlayer?.release() } catch (_: Exception) {}
        try { finishAndRemoveTask() } catch (_: Exception) { finishAffinity() }
        try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Exception) {}
        try { System.exit(0) } catch (_: Exception) {}
    }
}
