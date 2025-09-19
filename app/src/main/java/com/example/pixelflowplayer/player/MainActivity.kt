package com.example.pixelflowplayer.player

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.example.pixelflowplayer.R
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.*

// Sealed class for download status reporting
sealed class DownloadStatus {
    data class Progress(val percentageComplete: Int, val itemsDownloaded: Int, val totalItems: Int, val currentItemName: String) : DownloadStatus()
    data class ItemError(val itemName: String, val errorMessage: String) : DownloadStatus()
    object Success : DownloadStatus() // Indicates all downloads completed successfully
    data class Failed(val criticalErrorMessage: String) : DownloadStatus() // Indicates a failure that prevents playback
}

@UnstableApi class MainActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var rootLayout: FrameLayout
    private lateinit var pairingView: View
    private lateinit var loadingBar: ProgressBar
    private lateinit var mainStatusText: TextView
    private lateinit var urlText: TextView
    private lateinit var instructionsText: TextView
    private lateinit var exitButton: ImageView
    private lateinit var playerContainer: FrameLayout
    private var exoPlayer: ExoPlayer? = null
    private lateinit var videoPlayerView: PlayerView
    private lateinit var imagePlayerView: ImageView
    private lateinit var offlineIndicator: ImageView

    // --- Download Progress Views ---
    private lateinit var downloadProgressView: LinearLayout
    private lateinit var downloadMainStatusText: TextView
    private lateinit var downloadOverallProgressBar: ProgressBar
    private lateinit var downloadProgressPercentageText: TextView
    private lateinit var downloadItemsCountText: TextView
    private lateinit var downloadErrorDetailsText: TextView

    // --- Logic ---
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var deviceId: String
    private var currentPlaylist: List<PlaylistItem> = emptyList()
    private var currentItemIndex = 0
    private val playerHandler = Handler(Looper.getMainLooper())
    private var heartbeatJob: Job? = null
    private var flickerJob: Job? = null
    private val gson = Gson()
    private lateinit var connectivityManager: ConnectivityManager
    private var isNetworkCurrentlyConnected = false
    private val hideExitButtonHandler = Handler(Looper.getMainLooper())
    private val hideExitButtonRunnable = Runnable { exitButton.visibility = View.GONE }

    companion object {
        private const val KEY_PENDING_PAIRING_CODE = "pendingPairingCode"
        private const val TAG = "PixelFlowPlayer"
        private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024 // 8KB buffer for downloads
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sharedPreferences = getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE)
        findViews()
        setupFullscreen()
        setupInteractions()
        deviceId = getOrCreateDeviceId()
        setupNetworkMonitoring()
        isNetworkCurrentlyConnected = isInitialNetworkConnected()
        startSplashScreenFlow()
        startPeriodicNetworkCheck()
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
        videoPlayerView = findViewById(R.id.video_player_view)
        imagePlayerView = findViewById(R.id.image_player_view)

        // Download Progress Views
        downloadProgressView = findViewById(R.id.download_progress_view)
        downloadMainStatusText = findViewById(R.id.download_main_status_text)
        downloadOverallProgressBar = findViewById(R.id.download_overall_progress_bar)
        downloadProgressPercentageText = findViewById(R.id.download_progress_percentage_text)
        downloadItemsCountText = findViewById(R.id.download_items_count_text)
        downloadErrorDetailsText = findViewById(R.id.download_error_details_text)
    }

    // --- Download UI Helper Functions ---
    private fun showDownloadingScreen(totalItems: Int) {
        pairingView.visibility = View.GONE
        playerContainer.visibility = View.GONE
        loadingBar.visibility = View.GONE
        downloadProgressView.visibility = View.VISIBLE
        exitButton.visibility = View.GONE

        downloadMainStatusText.text = getString(R.string.download_preparing)
        downloadOverallProgressBar.progress = 0
        downloadOverallProgressBar.max = 100 // Should always be 100 for percentage
        downloadProgressPercentageText.text = "0%"
        downloadItemsCountText.text = getString(R.string.download_item_progress, 0, totalItems, "...")
        downloadErrorDetailsText.text = ""
        downloadErrorDetailsText.visibility = View.GONE
    }

    private fun updateDownloadProgressUI(status: DownloadStatus.Progress) {
        downloadMainStatusText.text = getString(R.string.download_in_progress)
        downloadOverallProgressBar.progress = status.percentageComplete
        "${status.percentageComplete}%".also { downloadProgressPercentageText.text = it }
        downloadItemsCountText.text = getString(R.string.download_item_progress, status.itemsDownloaded, status.totalItems, status.currentItemName)
    }

    private fun showDownloadItemErrorUI(status: DownloadStatus.ItemError) {
        downloadMainStatusText.text = getString(R.string.download_errors_encountered)
        val currentErrors = downloadErrorDetailsText.text.toString()
        "$currentErrors\nError: ${status.itemName} - ${status.errorMessage}".trim()
            .also { downloadErrorDetailsText.text = it }
        downloadErrorDetailsText.visibility = View.VISIBLE
    }

    private fun showDownloadSuccessUI() {
        downloadMainStatusText.text = getString(R.string.download_complete)
        downloadOverallProgressBar.progress = 100
        "100%".also { downloadProgressPercentageText.text = it }
    }

    private fun showDownloadFailedUI(status: DownloadStatus.Failed) {
        downloadMainStatusText.text = getString(R.string.download_failed_critical)
        downloadErrorDetailsText.text = status.criticalErrorMessage
        downloadErrorDetailsText.visibility = View.VISIBLE
        downloadOverallProgressBar.progress = 0
        "Failed".also { downloadProgressPercentageText.text = it }
    }
    // --- End Download UI Helper Functions ---

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
        if (downloadProgressView.isVisible) return

        val currentlyPaired = sharedPreferences.getBoolean("isPaired", false)
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
        var code = sharedPreferences.getString(KEY_PENDING_PAIRING_CODE, null)
        if (code == null) {
            code = generatePairingCode()
            sharedPreferences.edit { putString(KEY_PENDING_PAIRING_CODE, code) }
        }
        return code
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = lifecycleScope.launch {
            var pairingCodeForHeartbeat = ""
            if (!sharedPreferences.getBoolean("isPaired", false)) {
                pairingCodeForHeartbeat = getOrCreatePendingPairingCode()
                withContext(Dispatchers.Main) {
                    if (!downloadProgressView.isVisible) {
                        showPairingScreen(pairingCodeForHeartbeat, getString(R.string.pairing_instructions_unpaired))
                    }
                }
            }
            while (isActive) {
                if (!isNetworkCurrentlyConnected) {
                    delay(15000); continue
                }
                try {
                    val request = HeartbeatRequest(deviceId, pairingCodeForHeartbeat)
                    val response = ApiClient.apiService.deviceHeartbeat(request)
                    if (response.isSuccessful) {
                        val heartbeatResponse = response.body()
                        val wasPaired = sharedPreferences.getBoolean("isPaired", false)
                        Log.d(TAG, "Heartbeat Success. Response: ${heartbeatResponse?.status}. Paired: $wasPaired")

                        if (wasPaired && heartbeatResponse?.status == "unpaired") {
                            handleUnpairingAndReset(); return@launch
                        }
                        when (heartbeatResponse?.status) {
                            "paired_waiting" -> {
                                if (!wasPaired) {
                                    sharedPreferences.edit { putBoolean("isPaired", true); remove(KEY_PENDING_PAIRING_CODE) }
                                    pairingCodeForHeartbeat = ""
                                }
                                withContext(Dispatchers.Main) {
                                    if (downloadProgressView.isVisible) downloadProgressView.visibility = View.GONE
                                    if (currentPlaylist.isNotEmpty()) {
                                        releasePlayer()
                                        currentPlaylist = emptyList()
                                    }
                                    showPairedScreen()
                                }
                            }
                            "playing" -> {
                                if (!sharedPreferences.getBoolean("isPaired", false)) {
                                    sharedPreferences.edit { putBoolean("isPaired", true); remove(KEY_PENDING_PAIRING_CODE) }
                                    pairingCodeForHeartbeat = ""
                                }

                                val newPlaylistFromServer = heartbeatResponse.playlist
                                val currentLocalPlaylist = getLocalPlaylist()

                                // --- DIAGNOSTIC LOGGING ---
                                Log.d(TAG, "DIAGNOSTIC: Server Playlist: ${gson.toJson(newPlaylistFromServer)}")
                                Log.d(TAG, "DIAGNOSTIC: Local Playlist: ${gson.toJson(currentLocalPlaylist)}")
                                // -------------------------

                                if (newPlaylistFromServer != null && newPlaylistFromServer.items.isNotEmpty()) {
                                    val playlistContentHasChanged = !newPlaylistFromServer.isContentEqualTo(currentLocalPlaylist)
                                    Log.d(TAG, "DIAGNOSTIC: Playlist content has changed: $playlistContentHasChanged") // Log the comparison result

                                    if (playlistContentHasChanged) {
                                        Log.d(TAG, "New or updated playlist detected. Preparing to download content.")
                                        withContext(Dispatchers.Main) {
                                            showDownloadingScreen(newPlaylistFromServer.items.size)
                                        }

                                        val downloadedPlaylist = downloadContentAndCreateLocalPlaylist(newPlaylistFromServer) { status ->
                                            withContext(Dispatchers.Main) {
                                                when (status) {
                                                    is DownloadStatus.Progress -> updateDownloadProgressUI(status)
                                                    is DownloadStatus.ItemError -> showDownloadItemErrorUI(status)
                                                    is DownloadStatus.Success -> showDownloadSuccessUI()
                                                    is DownloadStatus.Failed -> showDownloadFailedUI(status)
                                                }
                                            }
                                        }

                                        if (downloadedPlaylist != null) {
                                            saveLocalPlaylist(downloadedPlaylist)
                                            Log.d(TAG, "Download complete. Starting playback of new/updated playlist.")
                                            delay(2000)
                                            startPlayback(downloadedPlaylist)
                                        } else {
                                            Log.e(TAG, "Playlist download failed or was incomplete. Not starting playback.")
                                            delay(5000)
                                            if (isActive) {
                                                withContext(Dispatchers.Main) {
                                                    showPairingScreen(getOrCreatePendingPairingCode(), getString(R.string.pairing_instructions_unpaired_after_fail))
                                                }
                                            }
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            if (downloadProgressView.isVisible) downloadProgressView.visibility = View.GONE
                                        }
                                        if (exoPlayer == null || exoPlayer?.isPlaying == false) {
                                            if (currentLocalPlaylist != null && currentLocalPlaylist.items.isNotEmpty()) {
                                                Log.d(TAG, "Playlist content is same as local, but player was idle. Starting playback.")
                                                startPlayback(currentLocalPlaylist)
                                            } else {
                                                Log.w(TAG, "Playlist content same, but local is empty. Showing paired screen.")
                                                withContext(Dispatchers.Main) { showPairedScreen() }
                                            }
                                        } else {
                                            Log.d(TAG, "Playlist content is same as local, and player is already active. No change needed.")
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "Received 'playing' status but no valid playlist in response.")
                                    withContext(Dispatchers.Main) {
                                        if (downloadProgressView.isVisible) downloadProgressView.visibility = View.GONE
                                        if (currentPlaylist.isNotEmpty()) {
                                            releasePlayer()
                                            currentPlaylist = emptyList()
                                        }
                                        showPairedScreen()
                                    }
                                }
                            }
                        }
                    } else { Log.w(TAG, "Heartbeat Error: ${response.code()}") }
                } catch (e: Exception) { if (isActive) Log.e(TAG, "Heartbeat Exception: ${e.message}") }
                delay(15000)
            }
        }
    }

    private fun handleUnpairingAndReset() {
        stopHeartbeat()
        releasePlayer()
        currentPlaylist = emptyList()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                cleanupOldFiles(emptySet())
            }
            val newPairingCode = generatePairingCode()
            sharedPreferences.edit(commit = true) {
                putBoolean("isPaired", false)
                remove("localPlaylist")
                putString(KEY_PENDING_PAIRING_CODE, newPairingCode)
            }
            try {
                ApiClient.apiService.deviceHeartbeat(HeartbeatRequest(deviceId, newPairingCode))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send immediate heartbeat on reset.", e)
            }
            withContext(Dispatchers.Main) {
                downloadProgressView.visibility = View.GONE
                updateUI()
            }
        }
    }

    private suspend fun downloadContentAndCreateLocalPlaylist(
        remotePlaylist: Playlist,
        onProgressUpdate: suspend (DownloadStatus) -> Unit
    ): Playlist? {
        val localItems = mutableListOf<PlaylistItem>()
        val newFileNames = mutableSetOf<String>()
        var itemsSuccessfullyProcessed = 0
        val totalItems = remotePlaylist.items.size
        var lastReportedOverallPercentage = -1

        onProgressUpdate(DownloadStatus.Progress(0, 0, totalItems, "Starting..."))

        withContext(Dispatchers.IO) {
            remotePlaylist.items.forEach { item ->
                val itemName = item.url.substringAfterLast('/')
                val initialOverallPercentage = (itemsSuccessfullyProcessed * 100) / totalItems
                if (initialOverallPercentage > lastReportedOverallPercentage) {
                    onProgressUpdate(
                        DownloadStatus.Progress(
                            initialOverallPercentage,
                            itemsSuccessfullyProcessed,
                            totalItems,
                            "Checking: $itemName"
                        )
                    )
                    lastReportedOverallPercentage = initialOverallPercentage
                }

                val localFile = File(filesDir, itemName)
                newFileNames.add(itemName)

                if (!localFile.exists()) {
                    try {
                        onProgressUpdate(DownloadStatus.Progress(initialOverallPercentage, itemsSuccessfullyProcessed, totalItems, "Downloading: $itemName"))
                        val connection = URL(item.url).openConnection()
                        val fileSize = connection.contentLengthLong
                        var bytesCopied = 0L

                        connection.getInputStream().use { input ->
                            FileOutputStream(localFile).use { output ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                var bytes = input.read(buffer)
                                while (bytes >= 0) {
                                    output.write(buffer, 0, bytes)
                                    bytesCopied += bytes
                                    bytes = input.read(buffer)

                                    val currentItemPercentage = if (fileSize > 0) ((bytesCopied * 100) / fileSize).toInt() else 0
                                    val overallPercentageFromCompleted = (itemsSuccessfullyProcessed * 100)
                                    val overallPercentage = (overallPercentageFromCompleted + currentItemPercentage) / totalItems

                                    if (overallPercentage > lastReportedOverallPercentage) {
                                        onProgressUpdate(DownloadStatus.Progress(overallPercentage, itemsSuccessfullyProcessed, totalItems, "Downloading: $itemName ($currentItemPercentage%)"))
                                        lastReportedOverallPercentage = overallPercentage
                                    }
                                }
                            }
                        }
                        Log.d(TAG, "Download complete for ${item.url}")
                        itemsSuccessfullyProcessed++
                    } catch (e: Exception) {
                        Log.e(TAG, "Download failed for ${item.url}", e)
                        localFile.delete()
                        onProgressUpdate(DownloadStatus.ItemError(itemName, e.localizedMessage ?: "Unknown error"))
                        onProgressUpdate(DownloadStatus.Failed("Critical error: Failed to download: $itemName. Aborting playlist."))
                        return@withContext
                    }
                } else {
                    Log.d(TAG, "File already exists: $itemName")
                    itemsSuccessfullyProcessed++
                    val overallPercentage = (itemsSuccessfullyProcessed * 100) / totalItems
                    if (overallPercentage > lastReportedOverallPercentage) {
                        onProgressUpdate(DownloadStatus.Progress(overallPercentage, itemsSuccessfullyProcessed, totalItems, "Exists: $itemName"))
                        lastReportedOverallPercentage = overallPercentage
                    }
                }
                localItems.add(item.copy(url = localFile.toURI().toString()))
            }
            cleanupOldFiles(newFileNames)
        }

        return if (itemsSuccessfullyProcessed == totalItems && localItems.size == totalItems) {
            onProgressUpdate(DownloadStatus.Success)
            Playlist(items = localItems, orientation = remotePlaylist.orientation)
        } else {
            if (itemsSuccessfullyProcessed < totalItems) {
                onProgressUpdate(DownloadStatus.Failed("Not all playlist items could be processed."))
            }
            null
        }
    }

    private fun cleanupOldFiles(currentFileNames: Set<String>) {
        filesDir.listFiles()?.forEach { file ->
            if (!currentFileNames.contains(file.name)) {
                file.delete()
            }
        }
    }

    private fun saveLocalPlaylist(playlist: Playlist) {
        val jsonString = gson.toJson(playlist)
        sharedPreferences.edit { putString("localPlaylist", jsonString) }
    }

    private fun getLocalPlaylist(): Playlist? {
        val jsonString = sharedPreferences.getString("localPlaylist", null) ?: return null
        return try {
            gson.fromJson(jsonString, Playlist::class.java)
        } catch (e: Exception) { Log.e(TAG, "Error parsing local playlist from SharedPreferences", e); null }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun generatePairingCode(): String {
        return (1..6).map { (('A'..'Z') + ('0'..'9')).random() }.joinToString("")
    }

    private fun startPlayback(playlist: Playlist) {
        runOnUiThread {
            setScreenOrientation(playlist.orientation) // Set orientation
            downloadProgressView.visibility = View.GONE
            if (playerContainer.visibility != View.VISIBLE) showPlayerScreen()
            currentPlaylist = playlist.items
            currentItemIndex = 0
            if (currentPlaylist.isNotEmpty()) playNextItem()
        }
    }

    private fun playNextItem() {
        if (currentPlaylist.isEmpty()) return
        if (currentItemIndex >= currentPlaylist.size) {
            currentItemIndex = 0 // Loop playlist
        }
        val item = currentPlaylist[currentItemIndex]
        currentItemIndex++
        when (item.type.lowercase()) {
            "video" -> playVideo(item)
            "image" -> showImage(item)
        }
    }

    private fun playVideo(item: PlaylistItem) {
        releasePlayer()
        videoPlayerView.visibility = View.VISIBLE
        imagePlayerView.visibility = View.GONE

        // --- NEW: Set Resize Mode based on displayMode ---
        videoPlayerView.resizeMode = when (item.displayMode?.lowercase()) {
            "cover" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            "contain" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT // Default
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val attributionTag = "mediaPlayback"
            val attributionContext = createAttributionContext(attributionTag)
            exoPlayer = ExoPlayer.Builder(attributionContext).build()
        } else {
            exoPlayer = ExoPlayer.Builder(this).build()
        }

        exoPlayer?.apply {
            videoPlayerView.player = this
            setMediaItem(MediaItem.fromUri(item.url))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) playNextItem()
                }
            })
            prepare()
            play()
        }
        if (item.duration > 0) playerHandler.postDelayed({ playNextItem() }, item.duration * 1000L)
    }

    private fun showImage(item: PlaylistItem) {
        releasePlayer()
        videoPlayerView.visibility = View.GONE
        imagePlayerView.visibility = View.VISIBLE

        // --- NEW: Set Scale Type based on displayMode ---
        imagePlayerView.scaleType = when (item.displayMode?.lowercase()) {
            "cover" -> ImageView.ScaleType.CENTER_CROP
            "fill" -> ImageView.ScaleType.FIT_XY
            "contain" -> ImageView.ScaleType.FIT_CENTER
            else -> ImageView.ScaleType.FIT_CENTER // Default
        }

        Glide.with(this).load(item.url).into(imagePlayerView)
        playerHandler.postDelayed({ playNextItem() }, item.duration * 1000L)
    }

    // --- NEW: Function to control screen orientation ---
    private fun setScreenOrientation(orientation: String?) {
        requestedOrientation = when (orientation?.lowercase()) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE // Default
        }
    }

    private fun releasePlayer() {
        playerHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeat()
        releasePlayer()
        flickerJob?.cancel()
    }

    private fun showLoadingScreen() {
        pairingView.visibility = View.GONE
        playerContainer.visibility = View.GONE
        downloadProgressView.visibility = View.GONE
        loadingBar.visibility = View.VISIBLE
        exitButton.visibility = View.GONE
    }

    private fun showPairingScreen(code: String, instruction: String) {
        playerContainer.visibility = View.GONE
        loadingBar.visibility = View.GONE
        downloadProgressView.visibility = View.GONE
        pairingView.visibility = View.VISIBLE
        mainStatusText.visibility = View.VISIBLE
        urlText.visibility = View.VISIBLE
        instructionsText.visibility = View.VISIBLE
        mainStatusText.text = code
        instructionsText.text = instruction
        exitButton.visibility = View.GONE
    }

    private fun showPairedScreen() {
        playerContainer.visibility = View.GONE
        loadingBar.visibility = View.GONE
        downloadProgressView.visibility = View.GONE
        pairingView.visibility = View.VISIBLE
        mainStatusText.visibility = View.VISIBLE
        urlText.visibility = View.VISIBLE
        instructionsText.visibility = View.VISIBLE
        mainStatusText.text = getString(R.string.paired_title)
        instructionsText.text = getString(R.string.pairing_instructions_paired)
        exitButton.visibility = View.GONE
    }

    private fun showPlayerScreen() {
        pairingView.visibility = View.GONE
        loadingBar.visibility = View.GONE
        downloadProgressView.visibility = View.GONE
        playerContainer.visibility = View.VISIBLE
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.exit_dialog_title))
            .setPositiveButton(getString(R.string.exit_dialog_exit_button)) { _, _ ->
                finishAffinity()
            }
            .setNegativeButton(getString(R.string.exit_dialog_cancel_button), null)
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInteractions() {
        exitButton.setOnClickListener { showExitConfirmationDialog() }
        rootLayout.setOnTouchListener { view, _ ->
            showExitButtonTemporarily()
            view.performClick()
            false
        }
        rootLayout.setOnHoverListener { _, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_HOVER_MOVE) {
                showExitButtonTemporarily()
            }
            false
        }
    }

    private fun showExitButtonTemporarily() {
        exitButton.visibility = View.VISIBLE
        hideExitButtonHandler.removeCallbacks(hideExitButtonRunnable)
        hideExitButtonHandler.postDelayed(hideExitButtonRunnable, 10000)
    }

    private fun getOrCreateDeviceId(): String {
        var id = sharedPreferences.getString("deviceId", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            sharedPreferences.edit { putString("deviceId", id) }
        }
        return id
    }

    private fun isInitialNetworkConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun setupFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }
}