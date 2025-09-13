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
import androidx.core.view.isVisible

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
    private var flickerJob: Job? = null
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
                    showPairingScreen(pairingCodeForHeartbeat, getString(R.string.pairing_instructions_unpaired))
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
                                    withContext(Dispatchers.Main) { showPairedScreen() }
                                } else if (currentPlaylist.isNotEmpty()) {
                                    releasePlayer()
                                    currentPlaylist = emptyList()
                                    withContext(Dispatchers.Main) { showPairedScreen() }
                                 }
                            }
                            "playing" -> {
                                val wasPreviouslyPairedButWaiting = sharedPreferences.getBoolean("isPaired", false) && pairingView.isVisible
                                if (!sharedPreferences.getBoolean("isPaired", false)) { // If it wasn't paired at all before
                                    sharedPreferences.edit { putBoolean("isPaired", true); remove(KEY_PENDING_PAIRING_CODE) }
                                    pairingCodeForHeartbeat = ""
                                }

                                val newPlaylistFromServer = heartbeatResponse.playlist
                                val currentLocalPlaylist = getLocalPlaylist() // Playlist from SharedPreferences

                                if (newPlaylistFromServer != null && newPlaylistFromServer.items.isNotEmpty()) {
                                    val playlistContentHasChanged = !newPlaylistFromServer.isContentEqualTo(currentLocalPlaylist)

                                    if (playlistContentHasChanged) {
                                        Log.d(TAG, "New or updated playlist detected. Downloading content.")
                                        // Show a loading/paired screen briefly while downloading
                                        withContext(Dispatchers.Main) { if (!playerContainer.isVisible) showPairedScreen() }
                                        val downloadedPlaylist = downloadContentAndCreateLocalPlaylist(newPlaylistFromServer)
                                        saveLocalPlaylist(downloadedPlaylist) // Save the new one to SharedPreferences
                                        Log.d(TAG, "Download complete. Starting playback of new/updated playlist.")
                                        startPlayback(downloadedPlaylist)
                                    } else {
                                        // Playlist content is the same as what's in SharedPreferences.
                                        // If we were showing the pairing/waiting screen, or if the player is not active,
                                        // we should start playback with this currentLocalPlaylist.
                                        if (wasPreviouslyPairedButWaiting || exoPlayer == null || exoPlayer?.isPlaying == false) {
                                            if (currentLocalPlaylist != null && currentLocalPlaylist.items.isNotEmpty()) {
                                                Log.d(TAG, "Playlist content is same as local, but player was idle or in waiting screen. Starting playback.")
                                                startPlayback(currentLocalPlaylist)
                                            } else {
                                                // This is an unlikely case: server says play, content is "same", but local is empty.
                                                // Fallback to showing paired screen.
                                                Log.w(TAG, "Playlist content same, but local is empty. Showing paired screen.")
                                                withContext(Dispatchers.Main) { showPairedScreen() }
                                            }
                                        } else {
                                            Log.d(TAG, "Playlist content is same as local, and player is already active. No change needed.")
                                        }
                                    }
                                } else {
                                    // Server said "playing" but sent no playlist or an empty one.
                                    Log.w(TAG, "Received 'playing' status but no valid playlist in response.")
                                    if (currentPlaylist.isNotEmpty()) { // If something was playing
                                        releasePlayer()
                                        currentPlaylist = emptyList()
                                    }
                                    withContext(Dispatchers.Main) { showPairedScreen() } // Go to "paired, waiting"
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
                updateUI()
            }
        }
    }

    private suspend fun downloadContentAndCreateLocalPlaylist(remotePlaylist: Playlist): Playlist {
        val localItems = mutableListOf<PlaylistItem>()
        val newFileNames = mutableSetOf<String>()
        withContext(Dispatchers.IO) {
            remotePlaylist.items.forEach { item ->
                val fileName = item.url.substringAfterLast('/')
                newFileNames.add(fileName)
                val localFile = File(filesDir, fileName)
                if (!localFile.exists()) {
                    try {
                        URL(item.url).openStream().use { input ->
                            FileOutputStream(localFile).use { output -> input.copyTo(output) }
                        }
                    } catch (e: Exception) { Log.e(TAG, "Download failed for ${item.url}", e) }
                }
                localItems.add(item.copy(url = localFile.toURI().toString()))
            }
            cleanupOldFiles(newFileNames)
        }
        return Playlist(items = localItems)
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
            if (playerContainer.visibility != View.VISIBLE) showPlayerScreen()
            currentPlaylist = playlist.items
            currentItemIndex = 0
            if (currentPlaylist.isNotEmpty()) playNextItem()
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

        // This line checks the version of Android the phone is running
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            // IF the phone is Android 11 or newer, this code runs.
            // It creates the attribution context to fix the error on new devices.
            val attributionTag = "mediaPlayback"
            val attributionContext = createAttributionContext(attributionTag)
            exoPlayer = ExoPlayer.Builder(attributionContext).build()

        } else {

            // IF the phone is older (like Android 7, 8, 9, or 10), this code runs.
            // It creates the player the simple, old way, which works perfectly on these versions.
            exoPlayer = ExoPlayer.Builder(this).build()
        }

        // The rest of the code is the same for all versions
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
        flickerJob?.cancel()
    }

    private fun showLoadingScreen() {
        pairingView.visibility = View.GONE
        playerContainer.visibility = View.GONE
        loadingBar.visibility = View.VISIBLE
        exitButton.visibility = View.GONE
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
        exitButton.visibility = View.GONE
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
        exitButton.visibility = View.GONE
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