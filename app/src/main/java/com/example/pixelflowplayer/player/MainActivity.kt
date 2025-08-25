package com.example.pixelflowplayer.player

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private var isNetworkCurrentlyConnected = false
    private val hideExitButtonHandler = Handler(Looper.getMainLooper())
    private val hideExitButtonRunnable = Runnable { exitButton.visibility = View.GONE }

    companion object {
        private const val KEY_PENDING_PAIRING_CODE = "pendingPairingCode"
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
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val caps = connectivityManager.getNetworkCapabilities(network)
                val hasValidatedInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                if (hasValidatedInternet && !isNetworkCurrentlyConnected) {
                    isNetworkCurrentlyConnected = true
                    runOnUiThread { updateUI() }
                } else if (!hasValidatedInternet && isNetworkCurrentlyConnected) {
                    // Became available but not validated, treat as disconnected if previously thought connected
                    isNetworkCurrentlyConnected = false
                    runOnUiThread { updateUI() }
                } else if (hasValidatedInternet && isNetworkCurrentlyConnected) {
                    // Still connected and validated, no need to update UI unless state changes
                } else {
                     // Not validated and wasn't connected, ensure UI reflects this
                    isNetworkCurrentlyConnected = false
                    runOnUiThread { updateUI() }
                }
            }
            override fun onLost(network: Network) {
                super.onLost(network)
                if (isNetworkCurrentlyConnected) {
                    isNetworkCurrentlyConnected = false
                    runOnUiThread { updateUI() }
                }
            }
        }
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        isNetworkCurrentlyConnected = isInitialNetworkConnected()
        startSplashScreenFlow()
    }

    private fun updateUI() {
        // 1. Set the offline indicator based on the most current network status
        if (isNetworkCurrentlyConnected) {
            offlineIndicator.visibility = View.GONE
        } else {
            offlineIndicator.visibility = View.VISIBLE
        }

        // 2. Proceed with device state logic (paired/unpaired)
        if (sharedPreferences.getBoolean("isPaired", false)) {
            // Paired Device Flow
            val localPlaylist = getLocalPlaylist()
            if (localPlaylist != null && localPlaylist.items.isNotEmpty()) {
                startPlayback(localPlaylist) // This shows player screen
            } else {
                showPairedScreen() // Shows "Paired, waiting for content"
            }

            if (isNetworkCurrentlyConnected) {
                startHeartbeat() // Try to sync with server
            } else {
                stopHeartbeat() // Don't try if offline
            }
        } else {
            // Unpaired Device Flow
            // The pairing screen and heartbeat attempt for unpaired devices are handled within startHeartbeat()
            // If offline, the indicator is already visible.
            // If online, the indicator is hidden.
            startHeartbeat() // This will call getOrCreatePendingPairingCode and showPairingScreen()
        }
    }


    private fun startSplashScreenFlow() {
        lifecycleScope.launch {
            showLoadingScreen()
            delay(10000) // Keep splash for a bit to allow network to settle
            isNetworkCurrentlyConnected = isInitialNetworkConnected() // Re-check network before first proper UI update
            updateUI()
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
            var pairingCode = ""
            if (!sharedPreferences.getBoolean("isPaired", false)) {
                pairingCode = getOrCreatePendingPairingCode()
                // Ensure pairing screen is shown immediately if unpaired,
                // even if offline, the pairing code needs to be visible.
                showPairingScreen(pairingCode, getString(R.string.pairing_instructions_unpaired))
            }
            while (true) {
                if (!isNetworkCurrentlyConnected) { // Check before API call
                    delay(15000) // Wait and retry if network is down
                    continue
                }
                try {
                    val request = HeartbeatRequest(deviceId, pairingCode)
                    val response = ApiClient.apiService.deviceHeartbeat(request)
                    if (response.isSuccessful) {
                        val heartbeatResponse = response.body()
                        val wasPaired = sharedPreferences.getBoolean("isPaired", false)
                        if (wasPaired && heartbeatResponse?.status == "unpaired") {
                            handleUnpairingAndReset()
                            return@launch
                        } else {
                            when (heartbeatResponse?.status) {
                                "paired_waiting" -> {
                                    sharedPreferences.edit {
                                        putBoolean("isPaired", true)
                                        remove(KEY_PENDING_PAIRING_CODE)
                                    }
                                    showPairedScreen()
                                }
                                "playing" -> {
                                    val newPlaylist = heartbeatResponse.playlist
                                    if (newPlaylist != null && newPlaylist != getLocalPlaylist()) {
                                        sharedPreferences.edit {
                                            putBoolean("isPaired", true)
                                            remove(KEY_PENDING_PAIRING_CODE)
                                        }
                                        showPairedScreen() // Show paired screen briefly during download
                                        delay(2000)
                                        val downloadedPlaylist = downloadContentAndCreateLocalPlaylist(newPlaylist)
                                        saveLocalPlaylist(downloadedPlaylist)
                                        startPlayback(downloadedPlaylist)
                                    } else if (newPlaylist != null && newPlaylist == getLocalPlaylist() && playerContainer.visibility != View.VISIBLE) {
                                        // If same playlist and player not visible, start playback
                                        startPlayback(newPlaylist)
                                    }
                                }
                            }
                        }
                    } else {
                         Log.d("Heartbeat", "Server responded with error: ${response.code()}. Retrying in 15s...")
                    }
                } catch (e: Exception) {
                    Log.d("Heartbeat", "Server connection failed: ${e.message}. Silently retrying in 15s...")
                }
                delay(15000)
            }
        }
    }

    private fun handleUnpairingAndReset() {
        stopHeartbeat()
        releasePlayer()
        currentPlaylist = emptyList()
        sharedPreferences.edit {
            remove("isPaired")
            remove("localPlaylist")
            remove(KEY_PENDING_PAIRING_CODE) // Also clear pending code on unpair
        }
        lifecycleScope.launch(Dispatchers.IO) { cleanupOldFiles(emptySet()) }
        // After unpairing, immediately attempt to get a new pairing code and show it.
        isNetworkCurrentlyConnected = isInitialNetworkConnected() // Re-check network
        updateUI() // This will trigger startHeartbeat which shows pairing screen
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
        filesDir.listFiles()?.forEach { file ->
            if (!currentFileNames.contains(file.name)) { file.delete() }
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
        } catch (_: Exception) { null }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun generatePairingCode(): String {
        val allowedChars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { allowedChars.random() }.joinToString("")
    }

    private fun startPlayback(playlist: Playlist) {
        showPlayerScreen()
        currentPlaylist = playlist.items
        currentItemIndex = 0
        if (currentPlaylist.isNotEmpty()) { playNextItem() }
    }

    private fun playNextItem() {
        if (currentPlaylist.isEmpty()) return
        if (currentItemIndex >= currentPlaylist.size) { currentItemIndex = 0 }
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
            setMediaItem(MediaItem.fromUri(item.url))
            videoPlayerView.player = this
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) { playNextItem() }
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
        connectivityManager.unregisterNetworkCallback(networkCallback)
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
