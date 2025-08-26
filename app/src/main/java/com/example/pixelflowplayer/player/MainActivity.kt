package com.example.pixelflowplayer.player

import android.annotation.SuppressLint
import android.content.SharedPreferences
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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

class MainActivity : AppCompatActivity() {

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

    // --- Logic ---
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var deviceId: String
    private var currentPlaylist: List<PlaylistItem> = emptyList()
    private var currentItemIndex = 0
    private val playerHandler = Handler(Looper.getMainLooper())
    private var heartbeatJob: Job? = null
    private val gson = Gson()
    private lateinit var connectivityManager: ConnectivityManager
    private var isNetworkCurrentlyConnected = false
    private val hideExitButtonHandler = Handler(Looper.getMainLooper())
    private val hideExitButtonRunnable = Runnable { exitButton.visibility = View.GONE }

    companion object {
        private const val KEY_PENDING_PAIRING_CODE = "pendingPairingCode"
        private const val TAG = "PixelFlowPlayer"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sharedPreferences = getSharedPreferences("PixelFlowPlayerPrefs", MODE_PRIVATE)
        findViews()
        setupFullscreen()
        setupInteractions()
        deviceId = getOrCreateDeviceId()

        // --- Network Monitoring Setup ---
        setupNetworkMonitoring()
        isNetworkCurrentlyConnected = isInitialNetworkConnected()
        Log.d(TAG, "onCreate: Initial network state: $isNetworkCurrentlyConnected")
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
                        Log.d(TAG, "Periodic network check: Status changed to $isNetworkCurrentlyConnected. Updating UI.")
                        updateUI()
                    }
                }
                delay(15000) // Check every 15 seconds
            }
        }
    }

    private fun updateUI() {
        val currentlyPaired = sharedPreferences.getBoolean("isPaired", false)
        Log.d(TAG, "updateUI() called. isNetworkCurrentlyConnected: $isNetworkCurrentlyConnected. isPaired from SharedPreferences: $currentlyPaired")

        if (isNetworkCurrentlyConnected) {
            offlineIndicator.visibility = View.GONE
        } else {
            offlineIndicator.visibility = View.VISIBLE
        }

        if (currentlyPaired) {
            Log.d(TAG, "updateUI: Device state is PAIRED.")
            val localPlaylist = getLocalPlaylist()
            if (localPlaylist != null && localPlaylist.items.isNotEmpty()) {
                startPlayback(localPlaylist)
            } else {
                showPairedScreen()
            }

            if (isNetworkCurrentlyConnected) {
                startHeartbeat()
            } else {
                stopHeartbeat()
            }
        } else {
            Log.d(TAG, "updateUI: Device state is UNPAIRED.")
            // Heartbeat must run to get pairing status, even if offline initially, to show the pairing code.
            startHeartbeat()
        }
    }

    private fun startSplashScreenFlow() {
        lifecycleScope.launch {
            showLoadingScreen()
            delay(5000) // Shorter delay, network check is now periodic
            isNetworkCurrentlyConnected = isInitialNetworkConnected()
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
            Log.d(TAG, "getOrCreatePendingPairingCode: Generated new code: $code")
        }
        return code
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) {
            Log.d(TAG, "startHeartbeat: Heartbeat job is already active. Skipping.")
            return
        }
        Log.d(TAG, "startHeartbeat: Starting new heartbeat job.")
        heartbeatJob = lifecycleScope.launch {
            var pairingCodeForHeartbeat: String

            val isDeviceCurrentlyPaired = sharedPreferences.getBoolean("isPaired", false)
            Log.d(TAG, "startHeartbeat (coroutine): Checking pairing status. isPaired: $isDeviceCurrentlyPaired")

            if (!isDeviceCurrentlyPaired) {
                Log.d(TAG, "startHeartbeat (coroutine): Device is NOT PAIRED. Will get/create code and show pairing screen.")
                pairingCodeForHeartbeat = getOrCreatePendingPairingCode()
                Log.d(TAG, "startHeartbeat (coroutine): Using pending pairing code: $pairingCodeForHeartbeat")
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "startHeartbeat (coroutine): Now calling showPairingScreen with code: $pairingCodeForHeartbeat")
                    showPairingScreen(pairingCodeForHeartbeat, getString(R.string.pairing_instructions_unpaired))
                }
            } else {
                Log.d(TAG, "startHeartbeat (coroutine): Device is PAIRED. Proceeding with paired heartbeat.")
                pairingCodeForHeartbeat = "" // Must be empty for paired heartbeats
            }

            while (isActive) {
                if (!isNetworkCurrentlyConnected) {
                    delay(15000)
                    continue
                }
                try {
                    val request = HeartbeatRequest(deviceId, pairingCodeForHeartbeat)
                    val response = ApiClient.apiService.deviceHeartbeat(request)
                    if (response.isSuccessful) {
                        val heartbeatResponse = response.body()
                        val wasPaired = sharedPreferences.getBoolean("isPaired", false)
                        Log.d(TAG, "Heartbeat: Success. Response: ${heartbeatResponse?.status}. Current paired state: $wasPaired")

                        if (wasPaired && heartbeatResponse?.status == "unpaired") {
                            handleUnpairingAndReset()
                            return@launch // Stop this coroutine, handleUnpairing will trigger a new UI flow
                        }

                        when (heartbeatResponse?.status) {
                            "paired_waiting" -> {
                                if (!wasPaired) {
                                    sharedPreferences.edit {
                                        putBoolean("isPaired", true)
                                        remove(KEY_PENDING_PAIRING_CODE)
                                    }
                                    pairingCodeForHeartbeat = "" // Stop sending the code
                                    withContext(Dispatchers.Main) { showPairedScreen() }
                                }
                            }
                            "playing" -> {
                                if (!wasPaired) {
                                    sharedPreferences.edit {
                                        putBoolean("isPaired", true)
                                        remove(KEY_PENDING_PAIRING_CODE)
                                    }
                                    pairingCodeForHeartbeat = "" // Stop sending the code
                                }
                                val newPlaylist = heartbeatResponse.playlist
                                if (newPlaylist != null && newPlaylist != getLocalPlaylist()) {
                                    withContext(Dispatchers.Main) { showPairedScreen() }
                                    delay(2000)
                                    val downloadedPlaylist = downloadContentAndCreateLocalPlaylist(newPlaylist)
                                    saveLocalPlaylist(downloadedPlaylist)
                                    startPlayback(downloadedPlaylist)
                                } else if (newPlaylist != null && playerContainer.visibility != View.VISIBLE) {
                                    startPlayback(newPlaylist)
                                }
                            }
                        }
                    } else {
                         Log.w("Heartbeat", "Server responded with error: ${response.code()}. Retrying in 15s...")
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e("Heartbeat", "Server connection failed: ${e.message}. Silently retrying in 15s...")
                    }
                }
                delay(15000)
            }
        }
    }

    // In MainActivity.kt

    private fun handleUnpairingAndReset() {
        Log.d(TAG, "handleUnpairingAndReset: Device is being unpaired and reset.")
        stopHeartbeat() // Stop the old loop
        releasePlayer()
        currentPlaylist = emptyList()

        // Launch a coroutine to handle async tasks (file cleanup and network call)
        lifecycleScope.launch {
            // Perform file cleanup in the background
            withContext(Dispatchers.IO) {
                cleanupOldFiles(emptySet())
            }

            // --- THE CORE FIX: GENERATE AND REPORT THE NEW CODE IMMEDIATELY ---

            // 1. Generate a brand new pairing code.
            val newPairingCode = generatePairingCode()
            Log.d(TAG, "handleUnpairingAndReset: Generated new pairing code: $newPairingCode")

            // 2. Synchronously update SharedPreferences with the new state.
            //    This ensures the new code is saved before any other process can run.
            sharedPreferences.edit(commit = true) {
                putBoolean("isPaired", false)
                remove("localPlaylist")
                putString(KEY_PENDING_PAIRING_CODE, newPairingCode)
            }
            Log.d(TAG, "handleUnpairingAndReset: SharedPreferences updated. isPaired=false, new pending code saved.")

            // 3. Immediately send a single, one-shot heartbeat to update the server.
            //    This synchronizes the server's database with the code now on screen.
            try {
                val request = HeartbeatRequest(deviceId, newPairingCode)
                ApiClient.apiService.deviceHeartbeat(request)
                Log.d(TAG, "handleUnpairingAndReset: Successfully sent immediate heartbeat with new code to server.")
            } catch (e: Exception) {
                Log.e(TAG, "handleUnpairingAndReset: Failed to send immediate heartbeat. Server will sync on next cycle.", e)
            }
            // --- END FIX ---

            // 4. Finally, update the UI on the main thread.
            withContext(Dispatchers.Main) {
                Log.d(TAG, "handleUnpairingAndReset: Calling updateUI to refresh state to UNPAIRED.")
                updateUI() // This will now start the normal heartbeat loop, which will see the new code.
            }
        }
    }

    private suspend fun downloadContentAndCreateLocalPlaylist(remotePlaylist: Playlist): Playlist {
        val localItems = mutableListOf<PlaylistItem>()
        val newFileNames = mutableSetOf<String>()
        withContext(Dispatchers.IO) {
            for (item in remotePlaylist.items) {
                val fileName = item.url.substringAfterLast('/')
                newFileNames.add(fileName)
                val localFile = File(filesDir, fileName)
                if (!localFile.exists()) {
                    try {
                        Log.d("Download", "Downloading ${item.url} to ${localFile.path}")
                        URL(item.url).openStream().use { input ->
                            FileOutputStream(localFile).use { output -> input.copyTo(output) }
                        }
                    } catch (e: Exception) { Log.e("Download", "Failed to download ${item.url}", e) }
                }
                val localItem = item.copy(url = localFile.toURI().toString())
                localItems.add(localItem)
            }
            cleanupOldFiles(newFileNames)
        }
        return Playlist(items = localItems)
    }

    private fun cleanupOldFiles(currentFileNames: Set<String>) {
        Log.d(TAG, "Cleanup: Cleaning old files. Keeping: [${currentFileNames.joinToString()}]")
        filesDir.listFiles()?.forEach { file ->
            if (!currentFileNames.contains(file.name)) {
                Log.d(TAG, "Cleanup: Deleting old file ${file.name}")
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse local playlist JSON", e)
            null
        }
    }

    private fun stopHeartbeat() {
        if (heartbeatJob?.isActive == true) {
            Log.d(TAG, "stopHeartbeat: Cancelling active heartbeat job.")
            heartbeatJob?.cancel()
        }
        heartbeatJob = null
    }

    private fun generatePairingCode(): String {
        val allowedChars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { allowedChars.random() }.joinToString("")
    }

    private fun startPlayback(playlist: Playlist) {
        runOnUiThread {
            showPlayerScreen()
            currentPlaylist = playlist.items
            currentItemIndex = 0
            if (currentPlaylist.isNotEmpty()) {
                playNextItem()
            }
        }
    }

    private fun playNextItem() {
        if (currentPlaylist.isEmpty()) return
        if (currentItemIndex >= currentPlaylist.size) {
            currentItemIndex = 0
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
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            videoPlayerView.player = this
            setMediaItem(MediaItem.fromUri(item.url))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        playNextItem()
                    }
                }
            })
            prepare()
            play()
        }
        if (item.duration > 0) {
            playerHandler.postDelayed({ playNextItem() }, item.duration * 1000L)
        }
    }

    private fun showImage(item: PlaylistItem) {
        releasePlayer()
        videoPlayerView.visibility = View.GONE
        imagePlayerView.visibility = View.VISIBLE
        Glide.with(this).load(item.url).into(imagePlayerView)
        playerHandler.postDelayed({ playNextItem() }, item.duration * 1000L)
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
    }

    private fun showLoadingScreen() {
        pairingView.visibility = View.GONE
        playerContainer.visibility = View.GONE
        loadingBar.visibility = View.VISIBLE
    }

    private fun showPairingScreen(code: String, instruction: String) {
        playerContainer.visibility = View.GONE
        loadingBar.visibility = View.GONE
        pairingView.visibility = View.VISIBLE
        mainStatusText.visibility = View.VISIBLE
        urlText.visibility = View.VISIBLE
        instructionsText.visibility = View.VISIBLE
        mainStatusText.text = code
        instructionsText.text = instruction
    }

    private fun showPairedScreen() {
        playerContainer.visibility = View.GONE
        loadingBar.visibility = View.GONE
        pairingView.visibility = View.VISIBLE
        mainStatusText.visibility = View.VISIBLE
        urlText.visibility = View.VISIBLE
        instructionsText.visibility = View.VISIBLE
        mainStatusText.text = getString(R.string.paired_title)
        instructionsText.text = getString(R.string.pairing_instructions_paired)
    }

    private fun showPlayerScreen() {
        pairingView.visibility = View.GONE
        loadingBar.visibility = View.GONE
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
