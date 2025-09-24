package com.example.pixelflowplayer

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
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
import com.example.pixelflowplayer.storage.StorageManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
        private const val BASE_URL = "http://10.0.2.2:3000"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        Log.d(TAG, getString(R.string.log_start_enhanced_integration))

        // Initialize SharedPreferences and device management (restored from old file)
        // val sharedPreferences = getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE) // Unused
        deviceId = getOrCreateDeviceId()

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
                    // Create device info for heartbeat
                    val deviceInfo = DeviceInfo(
                        model = Build.MODEL,
                        androidVersion = Build.VERSION.RELEASE,
                        appVersion = "1.0.0" // You can get this from BuildConfig if needed
                    )
                    val request = HeartbeatRequest(deviceId, pairingCodeForHeartbeat, deviceInfo)
                    val response = ApiClient.apiService.deviceHeartbeat(request)
                    if (response.isSuccessful) {
                        val heartbeatResponse = response.body()
                        val wasPaired = getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE).getBoolean("isPaired", false)
                        Log.d(TAG, "Heartbeat Success. Response: ${heartbeatResponse?.status}. Paired: $wasPaired")

                        if (wasPaired && heartbeatResponse?.status == "unpaired") {
                            handleUnpairingAndReset(); return@launch
                        }
                        when (heartbeatResponse?.status) {
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
                                withContext(Dispatchers.Main) { pairingView.visibility = View.GONE }

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
                    model = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE,
                    appVersion = "1.0.0"
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
        val mapped = source.items.mapNotNull { item ->
            val remoteUrl = if (item.url.startsWith("http") || item.url.startsWith("file://")) item.url else "$BASE_URL${item.url}"
            val file = storageManager.getCacheFile(remoteUrl)
            if (file.exists() && file.length() > 0) {
                item.copy(url = "file://${file.absolutePath}")
            } else null
        }
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

            withContext(Dispatchers.Main) {
                // Always show blocking download splash until 100% ready
                isBlockingOnDownload = true
                stopVideoPlayback()
                playerContainer.visibility = View.GONE
                showDownloadOverlay(allTargetUrls.size)

                saveLocalPlaylist(newPlaylist)
                playbackStartedFromDownloads = false
                downloadProgressJob?.cancel()
                // Track ALL items; cached ones will be counted as completed
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
                    withContext(Dispatchers.Main) {
                        updateDownloadOverlay(completedCount, urlsToTrack.size, percent, currentName, errors)
                    }

                    // When all items finished (completed + failed), start playback with cached-only items
                    if (completedCount + failedCount >= urlsToTrack.size) {
                        Log.d(TAG, "Downloads finished. Completed=$completedCount, Failed=$failedCount. Starting with cached items only.")
                        val freshPlaylist = (lastRemotePlaylist ?: getLocalPlaylist())?.let { buildLocalPlaylistFromCache(it) }
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
     * 🎬 Play the current playlist item (PlayerManager controls advancing)
     */
    private fun playCurrentItem() {
        if (currentPlaylistItems.isEmpty()) {
            Log.w(TAG, getString(R.string.log_no_items_to_play))
            statusText.text = getString(R.string.status_no_items_to_play)
            showPairedScreen()
            return
        }
        handler.removeCallbacksAndMessages(null)
        loadingBar.visibility = View.GONE
        if (!isBlockingOnDownload) hideDownloadOverlay()
        playerContainer.visibility = View.VISIBLE
        playerContainer.bringToFront()

        val item = currentPlaylistItems[currentItemIndex]
        Log.d(TAG, "🎬 Playing item ${currentItemIndex + 1}/${currentPlaylistItems.size}: ${item.url}")

        // Enforce offline-only playback: skip any non-local items
        if (!item.url.startsWith("file://")) {
            Log.w(TAG, "Skipping non-local item: ${item.url}")
            handler.post { advanceToNextItem() }
            return
        }

        val effectiveDisplayMode = item.displayMode ?: displayMode

        // Cancel any pending image advance
        imageAdvanceRunnable?.let { imagePlayerView.removeCallbacks(it) }
        imageAdvanceRunnable = null

        val uri = item.url

        if (item.type.equals("image", true)) {
            val durationSec = if (currentPlaylistItems.size == 1) 24 * 60 * 60 else maxOf(1, item.duration)
            stopVideoPlayback()
            playerView.visibility = View.GONE
            imagePlayerView.visibility = View.VISIBLE
            imagePlayerView.rotation = contentRotation.toFloat()
            imagePlayerView.scaleType = when (effectiveDisplayMode?.lowercase()) {
                "cover" -> ImageView.ScaleType.CENTER_CROP
                "fill" -> ImageView.ScaleType.FIT_XY
                else -> ImageView.ScaleType.FIT_CENTER
            }
            Glide.with(this)
                .load(uri)
                .skipMemoryCache(true)
                .into(imagePlayerView)

            val r = Runnable { advanceToNextItem() }
            imageAdvanceRunnable = r
            imagePlayerView.postDelayed(r, durationSec * 1000L)
        } else {
            imagePlayerView.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            applyVideoScalingAndRotation(effectiveDisplayMode, contentRotation)
            exoPlayer?.repeatMode = if (currentPlaylistItems.size == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            exoPlayer?.apply {
                stop()
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                play()
            }
        }
    }

    private fun applyVideoScalingAndRotation(mode: String?, rotation: Int) {
        val resizeMode = when (mode?.lowercase()) {
            "contain" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            "cover" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = resizeMode
        playerView.rotation = rotation.toFloat()
    }

    private fun applyContentRotation(rotationDegrees: Int) {
        val norm = ((rotationDegrees % 360) + 360) % 360
        if (contentRotation == norm) return
        Log.d(TAG, "Applying content rotation=${norm}° to media views")
        contentRotation = norm
        if (currentPlaylistItems.isNotEmpty()) {
            playCurrentItem()
        }
    }

    /**
     * Start playback for the given playlist using direct ExoPlayer/Glide pipeline
     */
    private fun startPlayback(playlist: Playlist) {
        runOnUiThread {
            applyPlaylistOrientation(playlist.orientation)
            if (!isBlockingOnDownload) hideDownloadOverlay()
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
            downloadMainStatusText.text = getString(R.string.download_overlay_downloading_item, currentItemName)
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
}
