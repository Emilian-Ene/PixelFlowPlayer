package com.example.pixelflowplayer

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import android.view.LayoutInflater
import android.view.ViewGroup
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
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.target.Target
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.TextureView
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import com.example.pixelflowplayer.admin.DeviceAdminReceiver as PfpDeviceAdminReceiver

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

    // NEW: Snapshot view for transitions
    private lateinit var transitionSnapshotView: ImageView

    // --- Download Progress Views (restored from old file) ---
    private lateinit var downloadProgressView: LinearLayout
    private lateinit var downloadMainStatusText: TextView
    private lateinit var downloadOverallProgressBar: ProgressBar
    private lateinit var downloadProgressPercentageText: TextView
    private lateinit var downloadItemsCountText: TextView
    private lateinit var downloadErrorDetailsText: TextView
    // New: per-item list container and row map
    private lateinit var downloadItemsContainer: LinearLayout
    private data class RowRefs(val root: View, val name: TextView, val status: TextView, val percent: TextView)
    private val downloadRowMap = mutableMapOf<String, RowRefs>()
    private val failedUrls = mutableSetOf<String>()

    // --- PlayerManager Integration (keeping modern approach) ---
    private lateinit var playerView: PlayerView
    private lateinit var imagePlayerView: ImageView
    private lateinit var statusText: TextView
    private var exoPlayer: ExoPlayer? = null
    private var imageAdvanceRunnable: Runnable? = null
    private var nowPlayingUpdateRunnable: Runnable? = null
    private var currentItemStartedAtMs: Long = 0L

    // Managers and services (restored)
    private lateinit var storageManager: StorageManager
    private lateinit var downloadManager: DownloadManager
    private lateinit var networkManager: NetworkManager
    private lateinit var connectivityManager: ConnectivityManager

    // Device and utils
    private lateinit var deviceId: String
    private val gson: Gson = Gson()
    private val handler: Handler = Handler(Looper.getMainLooper())

    // Network state
    private var isNetworkCurrentlyConnected: Boolean = false
    private var heartbeatJob: Job? = null
    private var flickerJob: Job? = null

    // Seamless video queue mapping: player media index -> playlist index
    private val mediaIndexToPlaylistIndex = mutableListOf<Int>()

    // Current playlist state
    private var currentPlaylistItems: List<PlaylistItem> = emptyList()
    private var currentItemIndex = 0
    private var contentRotation = 0
    private var displayMode: String? = "contain"
    // NEW: playlist-level transition type (Cut | Fade | Slide)
    private var currentTransitionType: String = "Cut"

    // Last remote playlist for tracking changes
    private var lastRemotePlaylist: Playlist? = null

    // Guard and job for download-complete handoff to playback
    private var downloadProgressJob: Job? = null
    private var playbackStartedFromDownloads = false
    private var isBlockingOnDownload: Boolean = false
    
    // Prevent overlapping clear-cache operations
    @Volatile private var isClearingCache: Boolean = false

    // Flag to stop new work when app is about to restart/finish
    @Volatile private var isShuttingDown: Boolean = false

    // Transient controls (exit button) visibility handler
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { exitButton.visibility = View.GONE }

    // Prevent starting a new update while a previous update is in progress
    private var playlistUpdateInProgress = false

    // NEW: orientation fallback state
    private var orientationCanvasOffset: Int = 0
    private var lastPlaylistOrientationNormalized: String? = null
    private var backendRotationDeg: Int = 0

    // NEW: preloading of the next video during image display
    private var preloadedVideoUrl: String? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_PENDING_PAIRING_CODE = "pendingPairingCode"
        // Use 10.0.2.2 for emulator (host machine), or 192.168.1.104 for physical device
        const val BASE_URL = "http://10.0.2.2:3000"
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
        // Suppress user-facing toasts; keep a one-time log entry only
        val key = filenameKey(urlOrPath)
        val first = synchronized(shownSkipToastKeys) { shownSkipToastKeys.add(key) }
        if (first) Log.i(TAG, msg)
    }

    // NEW: de-dup now_playing emissions per item
    private var lastNowPlayingKey: String? = null
    private var sentReadyUpdateForKey: Boolean = false

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
            wsManager = WebSocketManager.getInstance(host, port, deviceId)
            wsManager?.ensureConnected()
            // Wire remote command handling
            wsManager?.setCommandHandler { cmdId, command, params ->
                try {
                    handleRemoteCommand(cmdId, command, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle command $command", e)
                }
            }
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

            // NEW: force early content refresh shortly after (avoid waiting full heartbeat)
            Handler(Looper.getMainLooper()).postDelayed({
                try { fetchAndApplyLatestPlaylist(null) } catch (_: Exception) {}
            }, 1500L)
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
        // NEW: Recompute orientation fallback offset when OS orientation changes
        try {
            val osPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            orientationCanvasOffset = if (lastPlaylistOrientationNormalized == "portrait" && !osPortrait) 90 else 0
            Log.d(TAG, "Recomputed orientation canvas offset on config change: ${orientationCanvasOffset}° (osPortrait=${osPortrait})")
            // Re-apply rotation using latest backend rotation as base
            applyContentRotation(backendRotationDeg)
        } catch (_: Exception) {}
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

        // NEW: Snapshot view for transitions
        val existingSnapshot: ImageView? = try { findViewById(R.id.transition_snapshot_view) } catch (_: Exception) { null }
        if (existingSnapshot != null) {
            transitionSnapshotView = existingSnapshot
        } else {
            // Fallback: create programmatically to avoid NPE if layout variant missing the view
            transitionSnapshotView = ImageView(this).apply {
                visibility = View.GONE
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            playerContainer.addView(transitionSnapshotView)
        }

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
        // New list container
        downloadItemsContainer = findViewById(R.id.download_items_container)
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        // NEW: proactively reconnect WS on network changes
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    try { wsManager?.ensureConnected() } catch (_: Exception) {}
                }
                override fun onLost(network: Network) {
                    try { wsManager?.ensureConnected() } catch (_: Exception) {}
                }
            })
        } catch (_: Exception) { }
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
            // IMPORTANT: Map to cached file:// URLs; do not try to play remote URLs when server is down
            val playable = localPlaylist?.let { buildLocalPlaylistFromCache(it) }
            if (playable != null && playable.items.isNotEmpty()) {
                startPlayback(playable)
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
                                // NEW: fully reset remote snapshot and toasts
                                lastRemotePlaylist = null
                                synchronized(shownSkipToastKeys) { shownSkipToastKeys.clear() }
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
                                    // Do NOT force player visible yet. Keep it hidden until downloads are done or playback starts.
                                    pairingView.visibility = View.GONE
                                    // Leave download overlay state as-is; downloadAndPlayPlaylist will decide to show it
                                    playerContainer.visibility = View.GONE
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
                                            // CMS changed the playlist → purge old cache first, then download all new items
                                            downloadAndPlayPlaylist(newPlaylistFromServer, force = true, purgeBefore = true)
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
                                        // Background retry for any failed items still missing
                                        try {
                                            val allUrls = newPlaylistFromServer.items.map { item -> 
                                                if (item.url.startsWith("http")) item.url else "$BASE_URL${item.url}"
                                            }.toSet()
                                            retryFailedInBackground(allUrls)
                                        } catch (_: Exception) {}
                                    }
                                } else if (heartbeatResponse.playlist == null) {
                                    // Playlist was removed on server, stop playback and clear local snapshot
                                    withContext(Dispatchers.Main) {
                                        if (downloadProgressView.isVisible) downloadProgressView.visibility = View.GONE
                                        stopVideoPlayback()
                                        currentPlaylistItems = emptyList()
                                        currentItemIndex = 0
                                        getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).edit { remove("localPlaylist") }
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

    private fun friendlyNameFromUrl(url: String, playlist: Playlist?): String {
        return try {
            val last = url.substringAfterLast('/')
            val win = last.substringAfterLast('\\')
            val cleaned = win.substringBefore('?').substringBefore('#')
            val name = playlist?.items?.firstOrNull { it.url.substringAfterLast('/').substringAfterLast('\\').endsWith(cleaned, true) }?.displayName
            name ?: cleaned
        } catch (_: Exception) { url.substringAfterLast('/') }
    }

    private fun buildDownloadRows(urls: Set<String>, playlist: Playlist?) {
        runOnUiThread {
            downloadRowMap.clear()
            downloadItemsContainer.removeAllViews()
            val inflater = LayoutInflater.from(this)
            urls.forEach { url ->
                val row = inflater.inflate(R.layout.item_download_row, downloadItemsContainer, false)
                val name = row.findViewById<TextView>(R.id.text_name)
                val status = row.findViewById<TextView>(R.id.text_status)
                val percent = row.findViewById<TextView>(R.id.text_percent)
                name.text = friendlyNameFromUrl(url, playlist)
                status.text = "queued"
                percent.text = "0%"
                downloadItemsContainer.addView(row)
                downloadRowMap[url] = RowRefs(row, name, status, percent)
            }
        }
    }

    private fun markRowInProgress(url: String, progress: Float) {
        val refs = downloadRowMap[url] ?: return
        runOnUiThread {
            refs.status.text = "downloading"
            refs.status.setTextColor(resources.getColor(android.R.color.white))
            refs.percent.text = "${progress.toInt()}%"
        }
    }
    private fun markRowDone(url: String) {
        val refs = downloadRowMap[url] ?: return
        runOnUiThread {
            refs.status.text = "done"
            refs.status.setTextColor(resources.getColor(android.R.color.holo_green_light))
            refs.percent.text = "100%"
        }
    }
    private fun markRowFailed(url: String, message: String?) {
        val refs = downloadRowMap[url] ?: return
        runOnUiThread {
            refs.status.text = "failed"
            refs.status.setTextColor(resources.getColor(R.color.dark_red_error))
            if (refs.percent.text.toString() == "0%") refs.percent.text = "—"
        }
    }

    /**
     * 📥 Download all playlist items first, then start playback (enhanced for Playlist object)
     */
    private fun downloadAndPlayPlaylist(newPlaylist: Playlist, force: Boolean = false, purgeBefore: Boolean = false) {
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
            if (purgeBefore) {
                try { storageManager.clearCache() } catch (_: Exception) {}
                // remove saved local playlist snapshot
                getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).edit { remove("localPlaylist") }
            }
            val missingUrls = if (purgeBefore) {
                allTargetUrls
            } else {
                allTargetUrls.filter { url ->
                    val f = storageManager.getCacheFile(url)
                    !f.exists() || f.length() == 0L
                }.toSet()
            }

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
                isBlockingOnDownload = true
                stopVideoPlayback()
                playerContainer.visibility = View.GONE
                showDownloadOverlay(allTargetUrls.size)
                // Build rows for missing items
                buildDownloadRows(missingUrls, newPlaylist)

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
    private suspend fun monitorDownloadProgress(urlsToTrack: Set<String>) {
        // Ensure only one collector is active
        try { downloadProgressJob?.cancelAndJoin() } catch (_: CancellationException) {}
        val errors = mutableListOf<String>()
        downloadProgressJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                downloadManager.downloadProgress.collect { progressMap ->
                    val completedCount = urlsToTrack.count { url ->
                        val file = storageManager.getCacheFile(url)
                        file.exists() && file.length() > 0 || progressMap[url] is DownloadProgress.Completed
                    }
                    val failedCount = urlsToTrack.count { url -> progressMap[url] is DownloadProgress.Failed }

                    // Update per-item rows
                    urlsToTrack.forEach { url ->
                        when (val p = progressMap[url]) {
                            is DownloadProgress.InProgress -> {
                                markRowInProgress(url, p.progressPercent)
                            }
                            is DownloadProgress.Completed -> {
                                markRowDone(url)
                            }
                            is DownloadProgress.Failed -> {
                                failedUrls.add(url)
                                markRowFailed(url, p.error)
                            }
                            else -> { /* queued or null -> leave as is */ }
                        }
                    }

                    val percent = if (urlsToTrack.isNotEmpty()) (completedCount * 100) / urlsToTrack.size else 100
                    val inProgUrl = progressMap.entries.firstOrNull { it.value is DownloadProgress.InProgress }?.key
                    val fileBase = inProgUrl?.substringAfterLast('/')?.substringAfterLast('\\') ?: ""
                    val cleaned = fileBase.replace(Regex("^[0-9a-fA-F]{32}_"), "")
                    val currentNameFriendly = try {
                        val match = lastRemotePlaylist?.items?.firstOrNull { it.url.substringAfterLast('/')
                            .substringAfterLast('\\')
                            .endsWith(cleaned, ignoreCase = true) }
                        match?.displayName ?: cleaned
                    } catch (_: Exception) { cleaned }

                    // Emit realtime progress to CMS
                    try {
                        wsManager?.sendJson("download_progress", mapOf(
                            "completed" to completedCount,
                            "failed" to failedCount,
                            "total" to urlsToTrack.size,
                            "percent" to percent,
                            "currentName" to fileBase
                        ))
                    } catch (_: Exception) {}

                    withContext(Dispatchers.Main) {
                        updateDownloadOverlay(completedCount, urlsToTrack.size, percent, currentNameFriendly, emptyList())
                    }

                    if (completedCount + failedCount >= urlsToTrack.size) {
                        // All done
                        delay(350)
                        val freshPlaylist = (lastRemotePlaylist ?: getLocalPlaylist())?.let { buildLocalPlaylistFromCache(it) }
                        withContext(Dispatchers.Main) {
                            isBlockingOnDownload = false
                            hideDownloadOverlay()
                            if (freshPlaylist != null && freshPlaylist.items.isNotEmpty()) startPlayback(freshPlaylist) else showPairedScreen(instructions = getString(R.string.status_no_items_to_play))
                        }
                        playlistUpdateInProgress = false
                        cancel()
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.e(TAG, "Download monitor error", e)
            }
        }
    }

    /**
     * Start playback for the given playlist using direct ExoPlayer/Glide pipeline
     */
    private fun startPlayback(playlist: Playlist) {
        Log.d(TAG, "🎬 startPlayback() with ${playlist.items.size} items")
        runOnUiThread {
            if (!isActivityAlive()) return@runOnUiThread
            // Belt-and-suspenders: whenever playback starts, ensure overlay is gone and player is visible
            pairingView.visibility = View.GONE
            isBlockingOnDownload = false
            hideDownloadOverlay()
            loadingBar.visibility = View.GONE
            playerContainer.visibility = View.VISIBLE
            playerContainer.bringToFront()
            applyPlaylistOrientation(playlist.orientation)
            // NEW: store transition type from playlist
            currentTransitionType = playlist.transitionType.ifBlank { "Cut" }
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

    private fun isActivityAlive(): Boolean {
        if (isShuttingDown) return false
        if (isFinishing) return false
        return !(Build.VERSION.SDK_INT >= 17 && isDestroyed)
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
        val dlg = AlertDialog.Builder(this)
            // Use a clearer message similar to the provided reference
            .setMessage("Do you want to exit the player?")
             .setPositiveButton("EXIT") { _, _ ->
                 try { stopLockTask() } catch (_: Exception) {}
                 try { exoPlayer?.release() } catch (_: Exception) {}
                 finishAndRemoveTask(); finishAffinity()
             }
             .setNegativeButton("CANCEL") { dialog, _ ->
                 dialog.dismiss()
                 showTransientControls()
             }
             .setCancelable(true)
             .create()
        dlg.show()
        // Style buttons like simple text actions aligned right (no colored backgrounds)
        try {
            // Dark dialog background
            dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#424242")))
            // White message text
            dlg.findViewById<TextView>(android.R.id.message)?.setTextColor(android.graphics.Color.WHITE)
            
             val yes = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
             val no = dlg.getButton(AlertDialog.BUTTON_NEGATIVE)
             // Align container to the end (right)
             (yes.parent as? LinearLayout)?.apply {
                 gravity = android.view.Gravity.END
                 // Keep order: CANCEL then EXIT
             }
             // Helper to convert dp to px
             fun Int.dp() = (this * resources.displayMetrics.density).toInt()
             // Remove solid backgrounds; use text-only actions
             yes.setBackgroundColor(android.graphics.Color.TRANSPARENT)
             no.setBackgroundColor(android.graphics.Color.TRANSPARENT)
             // Subtle colors: EXIT accent, CANCEL default with pressed (hover-like) state
             val states = arrayOf(
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf()
            )
            val exitColors = intArrayOf(
                android.graphics.Color.parseColor("#26C6DA"), // pressed (lighter teal)
                android.graphics.Color.parseColor("#00BCD4")  // normal teal
            )
            val cancelColors = intArrayOf(
                android.graphics.Color.parseColor("#B0BEC5"), // pressed (lighter gray)
                android.graphics.Color.parseColor("#9E9E9E")  // normal gray
            )
            yes.setTextColor(android.content.res.ColorStateList(states, exitColors))
            no.setTextColor(android.content.res.ColorStateList(states, cancelColors))
             yes.isAllCaps = true
             no.isAllCaps = true
             // Add a little spacing between them
             (yes.layoutParams as? LinearLayout.LayoutParams)?.apply { setMargins(12.dp(), 8.dp(), 8.dp(), 8.dp()) }
             (no.layoutParams as? LinearLayout.LayoutParams)?.apply { setMargins(8.dp(), 8.dp(), 12.dp(), 8.dp()) }
         } catch (_: Exception) { /* best effort styling */ }
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

        if (!isActivityAlive()) return

        val item = currentPlaylistItems[currentItemIndex]
        Log.d(TAG, "🎬 Playing item ${currentPlaylistItems.size.takeIf { it>0 }?.let { currentItemIndex + 1 } ?: 0}/${currentPlaylistItems.size}: ${item.url}")

        // QUICK GUARD: ensure cached file exists before trying to play
        if (item.url.startsWith("file://")) {
            try {
                val path = item.url.removePrefix("file://")
                val f = File(path)
                if (!f.exists() || f.length() == 0L) {
                    Log.w(TAG, "Cached file missing/empty, skipping: $path")
                    // Silent skip
                    handler.post { advanceToNextItem() }
                    return
                }
            } catch (_: Exception) { /* ignore */ }
        }

        if (!isActivityAlive()) return

        // Emit now_playing for the new item
        try {
            val key = "${currentItemIndex}-${item.url}"
            if (lastNowPlayingKey != key) {
                wsManager?.sendJson("now_playing", mapOf(
                    "index" to currentItemIndex,
                    "mediaType" to item.type,
                    "url" to item.url,
                    "displayMode" to (item.displayMode ?: "contain"),
                    "rotation" to contentRotation,
                    // ms-based timing for accurate countdown
                    "positionMs" to 0,
                    "durationMs" to (item.duration * 1000),
                    "sentAt" to System.currentTimeMillis()
                ))
                lastNowPlayingKey = key
                sentReadyUpdateForKey = false
            }
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
        // Stop previous periodic updates
        nowPlayingUpdateRunnable?.let { handler.removeCallbacks(it) }
        nowPlayingUpdateRunnable = null
        currentItemStartedAtMs = SystemClock.uptimeMillis()

        // --- Universal Transition Logic --- //
        val currentlyPlayingView = when {
            playerView.isVisible -> playerView
            imagePlayerView.isVisible -> imagePlayerView
            else -> null
        }

        var snapshot: Bitmap? = null
        if (currentlyPlayingView != null) {
            snapshot = captureViewToBitmap(currentlyPlayingView)
        }

        if (snapshot != null) {
            transitionSnapshotView.setImageBitmap(snapshot)
            transitionSnapshotView.visibility = View.VISIBLE
            transitionSnapshotView.bringToFront()
        } else {
            transitionSnapshotView.visibility = View.GONE
            transitionSnapshotView.setImageDrawable(null)
        }

        // Ensure the actual player views are initially hidden or transparent
        playerView.visibility = View.GONE
        imagePlayerView.visibility = View.GONE
        playerView.alpha = 0f
        imagePlayerView.alpha = 0f

        if (item.type.equals("video", ignoreCase = true)) {
            // Video via ExoPlayer (seamless to next video)
            applyVideoDisplayMode(item.displayMode)
            try {
                if (preloadedVideoUrl != null && preloadedVideoUrl == item.url) {
                    // Already prepared while previous image was showing
                    preloadedVideoUrl = null
                    exoPlayer?.playWhenReady = true
                } else {
                    val items = mutableListOf<MediaItem>()
                    mediaIndexToPlaylistIndex.clear()
                    items.add(MediaItem.fromUri(item.url))
                    mediaIndexToPlaylistIndex.add(currentItemIndex)
                    val nextIdx = (currentItemIndex + 1) % currentPlaylistItems.size
                    val next = currentPlaylistItems.getOrNull(nextIdx)
                    if (next != null && next.type.equals("video", true)) {
                        items.add(MediaItem.fromUri(next.url))
                        mediaIndexToPlaylistIndex.add(nextIdx)
                    }
                    exoPlayer?.setMediaItems(items, /*resetPosition*/ true)
                    exoPlayer?.prepare()
                    exoPlayer?.playWhenReady = true
                }

                // If player is already READY (preloaded), reveal it now
                if (exoPlayer?.playbackState == Player.STATE_READY) {
                    imagePlayerView.visibility = View.GONE
                    playerContainer.visibility = View.VISIBLE
                    playerContainer.bringToFront()
                    playerView.visibility = View.VISIBLE
                    playerView.alpha = 1f
                    startContentTransition(playerView)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start video playback", e)
                handler.post { advanceToNextItem() }
            }
            // Schedule periodic now_playing_update while video plays
            nowPlayingUpdateRunnable = object : Runnable {
                override fun run() {
                    try {
                        val posMs = exoPlayer?.currentPosition ?: 0L
                        val durMs = exoPlayer?.duration ?: (item.duration * 1000L)
                        wsManager?.sendJson("now_playing_update", mapOf(
                            "index" to currentItemIndex,
                            "mediaType" to item.type,
                            "url" to item.url,
                            "displayMode" to (item.displayMode ?: "contain"),
                            "rotation" to contentRotation,
                            "positionMs" to posMs,
                            "durationMs" to durMs,
                            "sentAt" to System.currentTimeMillis()
                        ))
                    } catch (_: Exception) {}
                    handler.postDelayed(this, 1500)
                }
            }
            handler.postDelayed(nowPlayingUpdateRunnable!!, 1500)
        } else {
            // Image item: decode and show immediately
            loadingBar.visibility = View.GONE
            playerContainer.visibility = View.VISIBLE
            playerContainer.bringToFront()
            playerView.visibility = View.GONE
            imagePlayerView.visibility = View.VISIBLE
            imagePlayerView.alpha = 1f
            // Don't hide snapshot here - let startContentTransition handle it
            applyImageDisplayMode(item.displayMode)
            try {
                val path = item.url.removePrefix("file://")
                val bmp = android.graphics.BitmapFactory.decodeFile(path)
                if (bmp != null) {
                    imagePlayerView.setImageBitmap(bmp)
                } else {
                    Glide.with(imagePlayerView).load(item.url).into(imagePlayerView)
                }
            } catch (_: Exception) {
                try { Glide.with(imagePlayerView).load(item.url).into(imagePlayerView) } catch (_: Exception) {}
            }
            // Animate according to CMS transition (this will hide the snapshot)
            startContentTransition(imagePlayerView)

            // Preload next video in background to eliminate gap
            try {
                val next = currentPlaylistItems.getOrNull((currentItemIndex + 1) % currentPlaylistItems.size)
                if (next != null && next.type.equals("video", true)) {
                    exoPlayer?.setMediaItems(listOf(MediaItem.fromUri(next.url)), /*resetPosition*/ true)
                    exoPlayer?.prepare()
                    exoPlayer?.playWhenReady = false
                    preloadedVideoUrl = next.url
                } else {
                    preloadedVideoUrl = null
                }
            } catch (_: Exception) { preloadedVideoUrl = null }

            Log.d(TAG, "ImageView drawable set? ${imagePlayerView.drawable != null}")
            val seconds = if (item.duration > 0) item.duration else 8
            imageAdvanceRunnable = Runnable { advanceToNextItem() }
            handler.postDelayed(imageAdvanceRunnable!!, seconds * 1000L)
            nowPlayingUpdateRunnable = object : Runnable {
                override fun run() {
                    try {
                        wsManager?.sendJson("now_playing_update", mapOf(
                            "index" to currentItemIndex,
                            "mediaType" to item.type,
                            "url" to item.url,
                            "displayMode" to (item.displayMode ?: "contain"),
                            "rotation" to contentRotation,
                            "positionMs" to (SystemClock.uptimeMillis() - currentItemStartedAtMs),
                            "durationMs" to (seconds * 1000L),
                            "sentAt" to System.currentTimeMillis()
                        ))
                    } catch (_: Exception) {}
                    handler.postDelayed(this, 1500)
                }
            }
            handler.postDelayed(nowPlayingUpdateRunnable!!, 1500)
        }

        // NEW: Preload next image to reduce gaps
        try {
            val next = currentPlaylistItems.getOrNull((currentItemIndex + 1) % currentPlaylistItems.size)
            if (next != null && !next.url.isBlank() && next.type.equals("image", ignoreCase = true)) {
                Glide.with(imagePlayerView).load(next.url).preload()
            }
        } catch (_: Exception) {}
    }

    // Helper: capture a view's content to a Bitmap
    private fun captureViewToBitmap(view: View): Bitmap? {
        if (view.width == 0 || view.height == 0) return null

        // Special handling for PlayerView to get video frame
        if (view is PlayerView) {
            val videoSurfaceView = view.videoSurfaceView
            if (videoSurfaceView is TextureView) {
                return videoSurfaceView.bitmap
            } else {
                // Fallback for SurfaceView or other cases: draw the PlayerView
                val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                view.draw(canvas)
                return bitmap
            }
        }
        // General view snapshot
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    // Helper: orchestrate the actual transition
    private fun startContentTransition(newContentView: View) {
        val t = (currentTransitionType.ifBlank { "Cut" }).lowercase()
        val duration = when (t) { "fade" -> 250L; "slide" -> 280L; else -> 0L }

        // Show the new content view immediately (it might be transparent or off-screen initially)
        newContentView.visibility = View.VISIBLE

        if (transitionSnapshotView.isVisible && transitionSnapshotView.drawable != null) {
            when (t) {
                "fade" -> {
                    newContentView.alpha = 0f
                    newContentView.animate().alpha(1f).setDuration(duration).start()
                    transitionSnapshotView.animate().alpha(0f).setDuration(duration).withEndAction {
                        transitionSnapshotView.visibility = View.GONE
                        transitionSnapshotView.setImageDrawable(null)
                        transitionSnapshotView.alpha = 1f // Reset for next use
                    }.start()
                }
                "slide" -> {
                    val w = if (rootLayout.width > 0) rootLayout.width.toFloat() else newContentView.resources.displayMetrics.widthPixels.toFloat()
                    newContentView.translationX = w
                    newContentView.animate().translationX(0f).setDuration(duration).start()
                    transitionSnapshotView.animate().translationX(-w).alpha(0f).setDuration(duration).withEndAction {
                        transitionSnapshotView.visibility = View.GONE
                        transitionSnapshotView.setImageDrawable(null)
                        transitionSnapshotView.alpha = 1f // Reset for next use
                        transitionSnapshotView.translationX = 0f
                    }.start()
                }
                else -> { // Cut
                    transitionSnapshotView.visibility = View.GONE
                    transitionSnapshotView.setImageDrawable(null)
                }
            }
        } else { // No snapshot to transition out, just make new content visible
            newContentView.alpha = 1f
            newContentView.translationX = 0f
            transitionSnapshotView.visibility = View.GONE
            transitionSnapshotView.setImageDrawable(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isShuttingDown = true
        try { handler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { Glide.with(applicationContext).clear(imagePlayerView) } catch (_: Exception) {}
        nowPlayingUpdateRunnable?.let { try { handler.removeCallbacks(it) } catch (_: Exception) {} }
        try { wsManager?.disconnect() } catch (_: Exception) {}
        wsManager = null
    }

    override fun onResume() {
        super.onResume()
        try { wsManager?.ensureConnected() } catch (_: Exception) {}
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer
        try { playerView.setKeepContentOnPlayerReset(true) } catch (_: Exception) {}
        try { playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT) } catch (_: Exception) {}
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // Ensure the player surface is visible when frames are ready; hide image only now via transition
                    hideDownloadOverlay()
                    pairingView.visibility = View.GONE
                    playerContainer.visibility = View.VISIBLE
                    playerContainer.bringToFront()
                    playerView.visibility = View.VISIBLE
                    playerView.alpha = 1f
                    imagePlayerView.visibility = View.GONE
                    startContentTransition(playerView)

                    // NEW: when a video is ready, send one-time metadata update (duration) without duplicating now_playing
                    try {
                        val current = currentPlaylistItems.getOrNull(currentItemIndex)
                        if (current != null && current.type.equals("video", ignoreCase = true)) {
                            val durMs = exoPlayer?.duration ?: -1L
                            if (durMs > 0) {
                                val key = "${currentItemIndex}-${current.url}"
                                if (lastNowPlayingKey == key && !sentReadyUpdateForKey) {
                                    wsManager?.sendJson("now_playing_update", mapOf(
                                        "index" to currentItemIndex,
                                        "mediaType" to current.type,
                                        "url" to current.url,
                                        "displayMode" to (current.displayMode ?: "contain"),
                                        "rotation" to contentRotation,
                                        "positionMs" to (exoPlayer?.currentPosition ?: 0L),
                                        "durationMs" to durMs,
                                        "sentAt" to System.currentTimeMillis()
                                    ))
                                    sentReadyUpdateForKey = true
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                if (playbackState == Player.STATE_ENDED) {
                    // No more queued video -> advance to next (likely image or end)
                    advanceToNextItem()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Exo auto-advanced to the next queued video
                val mediaIdx = exoPlayer?.currentMediaItemIndex ?: 0
                val newIdx = mediaIndexToPlaylistIndex.getOrNull(mediaIdx) ?: return
                currentItemIndex = newIdx
                currentItemStartedAtMs = SystemClock.uptimeMillis()
                // Send now_playing for the new video item
                try {
                    val item = currentPlaylistItems.getOrNull(currentItemIndex)
                    if (item != null) {
                        val key = "${currentItemIndex}-${item.url}"
                        if (lastNowPlayingKey != key) {
                            wsManager?.sendJson("now_playing", mapOf(
                                "index" to currentItemIndex,
                                "mediaType" to item.type,
                                "url" to item.url,
                                "displayMode" to (item.displayMode ?: "contain"),
                                "rotation" to contentRotation,
                                "positionMs" to 0,
                                "durationMs" to (exoPlayer?.duration ?: (item.duration * 1000)),
                                "sentAt" to System.currentTimeMillis()
                            ))
                            lastNowPlayingKey = key
                            sentReadyUpdateForKey = false
                        }
                    }
                } catch (_: Exception) {}
                // Ensure next video is pre-queued (keep a buffer of 1 ahead)
                try {
                    val nextPlIdx = (currentItemIndex + 1) % currentPlaylistItems.size
                    if (mediaIndexToPlaylistIndex.size <= mediaIdx + 1) {
                        val next = currentPlaylistItems.getOrNull(nextPlIdx)
                        if (next != null && next.type.equals("video", true)) {
                            exoPlayer?.addMediaItem(MediaItem.fromUri(next.url))
                            mediaIndexToPlaylistIndex.add(nextPlIdx)
                        }
                    }
                } catch (_: Exception) {}
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
                            // Remove corrupt file to avoid repeated failures; no toast
                            f.delete()
                            Log.w(TAG, "Deleted corrupt cached file: ${f.name}")
                        }
                    } catch (_: Exception) {}
                    // Skip to next item so UI doesn’t hang (no auto re-download)
                    handler.post { advanceToNextItem() }
                }
            }
        })
    }

    private fun stopVideoPlayback() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
    }

    private fun applyPlaylistOrientation(orientation: String?) {
        val normalized = orientation?.trim()?.lowercase()
        lastPlaylistOrientationNormalized = normalized
        val newOrientation = when (normalized) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != newOrientation) {
            Log.d(TAG, "Requesting orientation change to: ${normalized ?: "unspecified"}")
            requestedOrientation = newOrientation
        }
        // NEW: Fallback – if device refuses to switch to portrait (common on TV boxes),)
        // rotate the canvas by 90° so portrait content renders correctly.
        val osPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        orientationCanvasOffset = if (normalized == "portrait" && !osPortrait) 90 else 0
        Log.d(TAG, "Orientation canvas offset: ${orientationCanvasOffset}° (osPortrait=${osPortrait})")
        // Re-apply rotation with the new offset combined with backend rotation
        applyContentRotation(backendRotationDeg)
    }

    // NEW: apply content rotation from backend (0/90/180/270) + orientation fallback offset
    private fun applyContentRotation(rotationDegrees: Int) {
        val rot = ((rotationDegrees % 360) + 360) % 360 // normalize
        backendRotationDeg = rot
        val effective = ((rot + orientationCanvasOffset) % 360 + 360) % 360
        Log.d(TAG, "Applying content rotation: ${rot}° + offset ${orientationCanvasOffset}° = ${effective}°")
        // Remember the effective rotation so we can report it in now_playing
        contentRotation = effective
        // Rotate both the image and video containers
        imagePlayerView.rotation = effective.toFloat()
        playerView.rotation = effective.toFloat()
    }

    // NEW: orientationCanvasOffset getter for testing
    fun getOrientationCanvasOffset(): Int {
        return orientationCanvasOffset
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

    private fun adminComponent(): ComponentName {
        return ComponentName(this, PfpDeviceAdminReceiver::class.java)
    }

    private fun handleRemoteCommand(id: String, command: String, params: Map<String, Any?>) {
        // Log at debug here; WebSocketManager already logs a single INFO line per command
        Log.d(TAG, "CMS command received: $command params=$params")
        when (command) {
            "force_update", "reload_playlist" -> {
                // Send immediate result to CMS
                try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "ok")) } catch (_: Exception) {}
                runOnUiThread {
                    // Immediately fetch latest state from server, then apply
                    fetchAndApplyLatestPlaylist(id)
                }
            }
            "clear_cache" -> {
                if (isClearingCache) {
                    try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "ok", "note" to "already_clearing")) } catch (_: Exception) {}
                    return
                }
                isClearingCache = true
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // 1) Clear our media cache first
                        try { storageManager.clearCache() } catch (_: Exception) {}

                        // 2) Clear Glide disk cache BEFORE touching cacheDir so it has a valid directory
                        try { Glide.get(applicationContext).clearDiskCache() } catch (_: Exception) {}

                        // 3) Clear other app cache files, but keep Glide's folder to avoid races
                        try {
                            val dir = cacheDir
                            dir.listFiles()?.forEach { f ->
                                if (f.name == "image_manager_disk_cache") return@forEach
                                try { if (f.isDirectory) f.deleteRecursively() else f.delete() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}

                        withContext(Dispatchers.Main) {
                            // 4) Clear Glide memory cache on main thread
                            try { Glide.get(applicationContext).clearMemory() } catch (_: Exception) {}
                            stopVideoPlayback()
                            currentPlaylistItems = emptyList()
                            currentItemIndex = 0
                            try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "ok")) } catch (_: Exception) {}
                            // Immediately fetch latest content so playback can resume without waiting for heartbeat
                            try { fetchAndApplyLatestPlaylist(null) } catch (_: Exception) {}
                        }
                    } finally {
                        isClearingCache = false
                    }
                }
            }
            "set_volume" -> {
                val raw = (params["level"] ?: params["volume"]) ?: 50
                val pct = when (raw) {
                    is Number -> raw.toFloat()
                    is String -> raw.toFloatOrNull() ?: 50f
                    else -> 50f
                }
                val perc = if (pct <= 1f) (pct * 100f) else pct
                // Run volume change on main thread to avoid thread errors
                runOnUiThread {
                    try {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                       
                        val target = (perc.coerceIn(0f, 100f) / 100f * max).toInt().coerceIn(0, max)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        // Also set ExoPlayer volume immediately (0..1)
                        val exoVol = target.toFloat() / max.toFloat()
                        exoPlayer?.volume = exoVol
                        try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "ok", "level" to (perc.coerceIn(0f,100f).toInt()))) } catch (_: Exception) {}
                    } catch (e: Exception) {
                        try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "error", "error" to (e.message ?: "error"))) } catch (_: Exception) {}
                    }
                }
            }
            "request_now_playing" -> {
                try {
                    val idx = currentItemIndex
                    val item = currentPlaylistItems.getOrNull(idx)
                    if (item != null) {
                        val isVideo = item.type.equals("video", ignoreCase = true)
                        val posMs = if (isVideo) (exoPlayer?.currentPosition ?: 0L) else 0L
                        val durMs = if (isVideo) (exoPlayer?.duration ?: (item.duration * 1000L)) else (item.duration * 1000L)
                        wsManager?.sendJson("now_playing_update", mapOf(
                            "index" to idx,
                            "mediaType" to item.type,
                            "url" to item.url,
                            "displayMode" to (item.displayMode ?: "contain"),
                            "rotation" to contentRotation,
                            "duration" to item.duration,
                            "positionMs" to posMs,
                            "durationMs" to durMs,
                            "sentAt" to System.currentTimeMillis()
                        ))
                    }
                    try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "ok")) } catch (_: Exception) {}
                } catch (e: Exception) {
                    try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "error", "error" to (e.message ?: "error"))) } catch (_: Exception) {}
                }
            }
            "screenshot" -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // 1) Capture bitmap from current visible view (video or image)
                        val bmp: Bitmap? = try {
                            // Prefer TextureView bitmap from PlayerView if visible
                            var candidate: Bitmap? = null
                            if (playerView.visibility == View.VISIBLE) {
                                val vs = playerView.videoSurfaceView
                                if (vs is TextureView) {
                                    candidate = vs.bitmap
                                } else {
                                    // Fallback: draw PlayerView to bitmap
                                    val b = Bitmap.createBitmap(playerView.width.coerceAtLeast(1), playerView.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                    val c = Canvas(b)
                                    playerView.draw(c)
                                    candidate = b
                                }
                            } else if (imagePlayerView.visibility == View.VISIBLE && imagePlayerView.width > 0 && imagePlayerView.height > 0) {
                                val b = Bitmap.createBitmap(imagePlayerView.width, imagePlayerView.height, Bitmap.Config.ARGB_8888)
                                val c = Canvas(b)
                                imagePlayerView.draw(c)
                                candidate = b
                            }
                            candidate
                        } catch (_: Exception) { null }

                       

                        if (bmp == null) throw RuntimeException("screenshot_failed")

                        // 2) Downscale to max 1080p on longest edge to save bandwidth
                        val maxEdge = 1080
                        val scaled: Bitmap = try {
                            val w = bmp.width
                            val h = bmp.height
                                                       val scale = maxOf(w, h).toFloat() / maxEdge.toFloat()
                            if (scale > 1f) {
                                val nw = (w / scale).toInt().coerceAtLeast(1)
                                val nh = (h / scale).toInt().coerceAtLeast(1)
                                Bitmap.createScaledBitmap(bmp, nw, nh, true)
                            } else bmp
                        } catch (_: Exception) { bmp }

                        // 3) Encode JPEG
                        val bos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                        val bytes = bos.toByteArray()
                        try { if (scaled !== bmp) bmp.recycle() } catch (_: Exception) {}

                        // 4) Upload via OkHttp multipart
                        val client = OkHttpClient()
                        val url = "$BASE_URL/api/devices/$deviceId/screenshot"
                        val reqBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart(
                                "file",
                                "screenshot.jpg",
                                RequestBody.create("image/jpeg".toMediaType(), bytes)
                            ).build()
                        val req = Request.Builder().url(url).post(reqBody).build()
                        val resp = client.newCall(req).execute()
                        if (!resp.isSuccessful) throw RuntimeException("upload_failed ${resp.code}")
                        val body = resp.body?.string() ?: "{}"
                        val link = try {
                            val obj = JSONObject(body)
                            obj.optString("url").takeIf { it.isNotEmpty() }
                        } catch (_: Exception) { null }
                        // 5) Report back via WS
                        try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "ok", "url" to link)) } catch (_: Exception) {}
                    } catch (e: Exception) {
                        try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "error", "error" to (e.message ?: "error"))) } catch (_: Exception) {}
                    }
                }
            }
            "clear_app_data" -> {
                // Requires the app to be Device Owner or Profile Owner
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val isDo = dpm.isDeviceOwnerApp(packageName)
                    val isPo = try { dpm.isProfileOwnerApp(packageName) } catch (_: Exception) { false }
                    if (isDo || isPo) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            dpm.clearApplicationUserData(
                                adminComponent(),
                                packageName,
                                mainExecutor,
                                object : DevicePolicyManager.OnClearApplicationUserDataListener {
                                    override fun onApplicationUserDataCleared(pkg: String, succeeded: Boolean) {
                                        try {

                                                                                       wsManager?.sendJson(
                                                "command_result",
                                                mapOf("id" to id, "command" to command, "status" to if (succeeded) "ok" else "error", "error" to if (succeeded) null else "failed")
                                            )
                                        } catch (_: Exception) {}
                                    }
                                }
                            )
                        } else {
                            // API < 30: the old 3-arg method exists at runtime but was removed from the current SDK; call via reflection
                            try {
                                val method = DevicePolicyManager::class.java.getMethod(
                                    "clearApplicationUserData",
                                    ComponentName::class.java,
                                    String::class.java,
                                    Boolean::class.javaPrimitiveType
                                )
                                val result = method.invoke(dpm, adminComponent(), packageName, false) as? Boolean ?: false
                                try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to if (result) "ok" else "error")) } catch (_: Exception) {}
                            } catch (e: Exception) {
                                try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "error", "error" to (e.message ?: "error"))) } catch (_: Exception) {}
                            }
                        }
                    } else {
                        wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "error", "error" to "not_owner"))
                    }
                } catch (e: Exception) {
                    try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "error", "error" to (e.message ?: "error"))) } catch (_: Exception) {}
                }
            }
            "restart_app" -> {
                // Ack first, then schedule a safe restart
                try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "ok")) } catch (_: Exception) {}
                runOnUiThread {
                    // Stop any future work before we restart
                    isShuttingDown = true
                    try { handler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
                    imageAdvanceRunnable?.let { try { handler.removeCallbacks(it) } catch (_: Exception) {} }
                    imageAdvanceRunnable = null
                    try { stopVideoPlayback() } catch (_: Exception) {}
                    try { Glide.with(applicationContext).clear(imagePlayerView) } catch (_: Exception) {}
                    scheduleAppRestart(600)
                }
            }
            else -> {
                try { wsManager?.sendJson("command_result", mapOf("id" to id, "command" to command, "status" to "error", "error" to "unsupported_command")) } catch (_: Exception) {}
            }
        }
    }

    // Fetch latest playlist/rotation via heartbeat and apply instantly
    private fun fetchAndApplyLatestPlaylist(commandId: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
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
                val resp = ApiClient.apiService.deviceHeartbeat(HeartbeatRequest(deviceId, "", deviceInfo))
                if (resp.isSuccessful) {
                    val hb = resp.body()
                    when (hb?.status) {
                        "playing" -> {
                            val pl = hb.playlist
                            if (pl != null) {
                                withContext(Dispatchers.Main) {
                                    applyPlaylistOrientation(pl.orientation)
                                    applyContentRotation(hb.rotation ?: 0)
                                    downloadAndPlayPlaylist(pl, force = true)
                                }
                                try { wsManager?.sendJson("command_result", mapOf("id" to commandId, "status" to "ok")) } catch (_: Exception) {}
                            } else {
                                // No playlist even though status says playing; treat as waiting
                                withContext(Dispatchers.Main) { showPairedScreen() }
                                try { wsManager?.sendJson("command_result", mapOf("id" to commandId, "status" to "error", "error" to "no_playlist")) } catch (_: Exception) {}
                            }
                        }
                        "paired_waiting" -> {
                            withContext(Dispatchers.Main) { showPairedScreen() }
                            try { wsManager?.sendJson("command_result", mapOf("id" to commandId, "status" to "ok", "note" to "waiting")) } catch (_: Exception) {}
                        }
                        else -> {
                            // unpaired or unknown → explicitly show pairing code, avoid flashing 'Paired'
                            val code = getOrCreatePendingPairingCode()
                            getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).edit { putBoolean("isPaired", false) }
                            withContext(Dispatchers.Main) { showPairedScreen(code, getString(R.string.pairing_instructions_unpaired)) }
                            try { wsManager?.sendJson("command_result", mapOf("id" to commandId, "status" to "ok", "note" to (hb?.status ?: "unpaired"))) } catch (_: Exception) {}
                        }
                    }
                } else {
                    // Fallback to local snapshot if heartbeat failed
                    val pl = lastRemotePlaylist
                    if (pl != null) {
                        withContext(Dispatchers.Main) { downloadAndPlayPlaylist(pl, force = true) }
                        try { wsManager?.sendJson("command_result", mapOf("id" to commandId, "status" to "ok", "note" to "fallback_last_snapshot")) } catch (_: Exception) {}
                    } else {
                        try { wsManager?.sendJson("command_result", mapOf("id" to commandId, "status" to "error", "error" to "heartbeat_failed")) } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                // Network/other error → fallback
                val pl = lastRemotePlaylist
                if (pl != null) {
                    withContext(Dispatchers.Main) { downloadAndPlayPlaylist(pl, force = true) }
                    try { wsManager?.sendJson("command_result", mapOf("id" to commandId, "status" to "ok", "note" to "fallback_last_snapshot")) } catch (_: Exception) {}
                } else {
                    try { wsManager?.sendJson("command_result", mapOf("id" to commandId, "status" to "error", "error" to (e.message ?: "error"))) } catch (_: Exception) {}
                }
            }
        }
    }

    // Retry previously failed downloads in the background without blocking UI
    private fun retryFailedInBackground(allTargetUrls: Set<String>) {
        if (failedUrls.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            failedUrls.toList().forEach { url ->
                try {
                    // Only retry items that are part of the current playlist and still missing
                    if (url !in allTargetUrls) return@forEach
                    val f = storageManager.getCacheFile(url)
                    if (f.exists() && f.length() > 0) {
                        failedUrls.remove(url)
                        return@forEach
                    }
                    downloadManager.queueDownload(
                        DownloadRequest(url = url, priority = DownloadPriority.NORMAL)
                    )
                } catch (_: Exception) { }
            }
        }
    }

    private fun scheduleAppRestart(delayMs: Long = 500) {
        // Production-grade restart: AlarmManager bypasses Doze mode and battery optimization
        try {
            val ctx = applicationContext
            val launch = packageManager?.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                // Use CLEAR_TOP for better compatibility across Android versions
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                
                val pi = PendingIntent.getActivity(
                    ctx,
                    123, // Unique request code for restart operations
                    launch,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val triggerAt = System.currentTimeMillis() + delayMs
                
                // setExactAndAllowWhileIdle: Most reliable for physical devices
                // Works even in Doze mode and with aggressive battery optimization
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                
                Log.i(TAG, "⏰ Restart scheduled in ${delayMs}ms via AlarmManager")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule restart: ${e.message}")
        }

        // Clean up resources and finish activity
        try { exoPlayer?.release() } catch (_: Exception) {}
        try { 
            finishAffinity()
        } catch (_: Exception) {}
    }
}