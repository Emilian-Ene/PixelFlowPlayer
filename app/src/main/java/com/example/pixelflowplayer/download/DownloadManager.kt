package com.example.pixelflowplayer.download

import android.content.Context
import android.util.Log
import com.example.pixelflowplayer.network.NetworkManager
import com.example.pixelflowplayer.network.NetworkResult
import com.example.pixelflowplayer.storage.StorageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enhanced download manager with intelligent queuing, integrity validation, and progress tracking
 */
class DownloadManager(
    private val context: Context,
    private val storageManager: StorageManager,
    private val networkManager: NetworkManager
) {
    companion object {
        private const val TAG = "DownloadManager"
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        private const val DOWNLOAD_BUFFER_SIZE = 16 * 1024 // 16KB buffer
        private const val INTEGRITY_CHECK_ENABLED = true
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val DEFAULT_SIZE_ESTIMATE_BYTES = 10L * 1024 * 1024 // 10MB conservative default
    }
    
    private val activeDownloads = ConcurrentHashMap<String, DownloadTask>()
    private val downloadQueue = mutableListOf<DownloadRequest>()
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = _downloadProgress.asStateFlow()
    
    private val isProcessingQueue = AtomicBoolean(false)
    private var queueProcessingJob: Job? = null
    
    /**
     * Queue a download request
     */
    suspend fun queueDownload(request: DownloadRequest): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Queuing download: ${request.url}")
        
        // Check if already downloaded and valid
        val cachedFile = storageManager.getCacheFile(request.url)
        if (cachedFile.exists() && storageManager.validateCacheFile(cachedFile)) {
            Log.d(TAG, "File already exists and is valid: ${cachedFile.name}")
            updateProgress(request.url, DownloadProgress.Completed(cachedFile.absolutePath))
            return@withContext true
        }
        
        // Determine expected size (probe HEAD when not provided)
        val estimatedSize = request.expectedSize ?: (probeContentLength(request.url) ?: DEFAULT_SIZE_ESTIMATE_BYTES)
        
        // Ensure space, performing aggressive cleanup if needed
        val okSpace = storageManager.ensureSpaceForBytes(estimatedSize)
        if (!okSpace) {
            Log.w(TAG, "Insufficient storage space for ${request.url} even after cleanup")
            updateProgress(request.url, DownloadProgress.Failed("Insufficient storage space"))
            return@withContext false
        }
        
        // Add to queue
        synchronized(downloadQueue) {
            downloadQueue.add(request)
        }
        
        // Start processing if not already running
        startQueueProcessing()
        
        return@withContext true
    }
    
    /**
     * Download multiple files in batch
     */
    suspend fun queueBatchDownload(
        requests: List<DownloadRequest>,
        onBatchProgress: (completed: Int, total: Int, overallProgress: Float) -> Unit = { _, _, _ -> }
    ): BatchDownloadResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting batch download of ${requests.size} files")
        
        val results = mutableMapOf<String, Boolean>()
        var completedCount = 0
        
        // Queue all requests
        for (request in requests) {
            val success = queueDownload(request)
            if (!success) {
                results[request.url] = false
            }
        }
        
        // Monitor progress
        val progressJob = launch {
            while (completedCount < requests.size) {
                val currentProgress = _downloadProgress.value
                completedCount = requests.count { request ->
                    val progress = currentProgress[request.url]
                    progress is DownloadProgress.Completed || progress is DownloadProgress.Failed
                }
                
                val overallProgress = if (requests.isNotEmpty()) {
                    completedCount.toFloat() / requests.size
                } else 0f
                
                onBatchProgress(completedCount, requests.size, overallProgress)
                
                if (completedCount < requests.size) {
                    delay(500) // Update every 500ms
                }
            }
        }
        
        progressJob.join()
        
        val successful = requests.count { request ->
            _downloadProgress.value[request.url] is DownloadProgress.Completed
        }
        
        Log.d(TAG, "Batch download completed: $successful/${requests.size} successful")
        
        return@withContext BatchDownloadResult(
            totalRequests = requests.size,
            successfulDownloads = successful,
            failedDownloads = requests.size - successful,
            results = results
        )
    }
    
    /**
     * Cancel a specific download
     */
    suspend fun cancelDownload(url: String): Boolean {
        Log.d(TAG, "Cancelling download: $url")
        
        // Remove from queue
        synchronized(downloadQueue) {
            downloadQueue.removeAll { it.url == url }
        }
        
        // Cancel active download
        val task = activeDownloads[url]
        if (task != null) {
            task.cancel()
            activeDownloads.remove(url)
            updateProgress(url, DownloadProgress.Cancelled)
            return true
        }
        
        return false
    }
    
    /**
     * Cancel all downloads
     */
    suspend fun cancelAllDownloads() {
        Log.d(TAG, "Cancelling all downloads")
        
        synchronized(downloadQueue) {
            downloadQueue.clear()
        }
        
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        
        queueProcessingJob?.cancel()
        isProcessingQueue.set(false)
        
        // Update progress for all items
        val cancelledProgress = _downloadProgress.value.mapValues { 
            if (it.value is DownloadProgress.InProgress) DownloadProgress.Cancelled else it.value
        }
        _downloadProgress.value = cancelledProgress
    }
    
    /**
     * Get download statistics
     */
    fun getDownloadStats(): DownloadStats {
        val currentProgress = _downloadProgress.value
        val completed = currentProgress.values.count { it is DownloadProgress.Completed }
        val failed = currentProgress.values.count { it is DownloadProgress.Failed }
        val inProgress = currentProgress.values.count { it is DownloadProgress.InProgress }
        val queued = synchronized(downloadQueue) { downloadQueue.size }
        
        return DownloadStats(
            totalDownloads = currentProgress.size,
            completedDownloads = completed,
            failedDownloads = failed,
            inProgressDownloads = inProgress,
            queuedDownloads = queued,
            activeDownloads = activeDownloads.size
        )
    }
    
    /**
     * Start processing the download queue
     */
    private fun startQueueProcessing() {
        if (isProcessingQueue.get()) return
        
        isProcessingQueue.set(true)
        queueProcessingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isProcessingQueue.get()) {
                try {
                    processDownloadQueue()
                    delay(1000) // Check queue every second
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing download queue", e)
                    delay(5000) // Wait longer on error
                }
            }
        }
    }
    
    /**
     * Process items in the download queue
     */
    private suspend fun processDownloadQueue() {
        if (activeDownloads.size >= MAX_CONCURRENT_DOWNLOADS) {
            return // At capacity
        }
        
        val nextRequest = synchronized(downloadQueue) {
            downloadQueue.firstOrNull()?.also { downloadQueue.removeAt(0) }
        } ?: return
        
        // Start download task
        val task = DownloadTask(nextRequest, this::onDownloadComplete)
        activeDownloads[nextRequest.url] = task
        
        CoroutineScope(Dispatchers.IO).launch {
            executeDownload(task)
        }
    }
    
    /**
     * Execute a single download task
     */
    private suspend fun executeDownload(task: DownloadTask) {
        val request = task.request
        var attempt = 0
        var lastError: String? = null
        
        updateProgress(request.url, DownloadProgress.InProgress(0f, "Starting download..."))
        
        while (attempt < MAX_DOWNLOAD_ATTEMPTS && task.isActive) {
            try {
                Log.d(TAG, "Download attempt ${attempt + 1} for ${request.url}")
                
                val result = downloadFileWithProgress(request) { progress, status ->
                    if (task.isActive) {
                        updateProgress(request.url, DownloadProgress.InProgress(progress, status))
                    }
                }
                
                when (result) {
                    is DownloadResult.Success -> {
                        Log.d(TAG, "Download successful: ${request.url}")
                        updateProgress(request.url, DownloadProgress.Completed(result.filePath))
                        return
                    }
                    is DownloadResult.Error -> {
                        lastError = result.message
                        Log.w(TAG, "Download attempt failed: ${result.message}")
                    }
                }
                
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "Download attempt exception", e)
            }
            
            attempt++
            if (attempt < MAX_DOWNLOAD_ATTEMPTS && task.isActive) {
                val delay = 1000L * attempt // Increasing delay
                Log.d(TAG, "Retrying download in ${delay}ms...")
                delay(delay)
            }
        }
        
        if (task.isActive) {
            updateProgress(request.url, DownloadProgress.Failed(lastError ?: "Unknown error"))
        }
    }
    
    /**
     * Download file with progress tracking
     */
    private suspend fun downloadFileWithProgress(
        request: DownloadRequest,
        onProgress: (progress: Float, status: String) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            var currentUrl = request.url
            var redirects = 0
            val maxRedirects = 5
            var connection: HttpURLConnection

            while (true) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "PixelFlowPlayer/1.0")
                    setRequestProperty("Accept", "*/*")
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val loc = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (loc.isNullOrEmpty() || redirects++ >= maxRedirects) {
                        return@withContext DownloadResult.Error("Too many redirects or missing Location header")
                    }
                    currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                    continue
                }
                if (code !in 200..299) {
                    val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                    connection.disconnect()
                    return@withContext DownloadResult.Error("HTTP error: $code ${err.take(200)}")
                }
                break
            }

            val targetFile = storageManager.getCacheFile(request.url)
            val contentLength = connection.contentLengthLong

            connection.inputStream.use { raw ->
                BufferedInputStream(raw).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        var totalBytes = 0L
                        var bytesRead: Int
                        onProgress(0f, "Downloading...")
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            val progress = if (contentLength > 0) (totalBytes.toFloat() / contentLength) * 100f else 0f
                            val status = buildString {
                                append("Downloaded ${totalBytes / 1024}KB")
                                if (contentLength > 0) append(" of ${contentLength / 1024}KB")
                            }
                            onProgress(progress, status)
                        }
                        output.flush()
                    }
                }
            }
            connection.disconnect()

            // Validate downloaded file thoroughly
            val valid = storageManager.validateCacheFile(targetFile)
            if (!valid) {
                try { targetFile.delete() } catch (_: Exception) {}
                return@withContext DownloadResult.Error("Downloaded file failed validation (magic bytes/size)")
            }

            onProgress(100f, "Download complete")
            return@withContext DownloadResult.Success(targetFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Download error for ${request.url}", e)
            return@withContext DownloadResult.Error(e.message ?: "Download failed")
        }
    }
    
    /**
     * Validate downloaded file integrity
     */
    private suspend fun validateDownloadedFile(file: File, request: DownloadRequest): Boolean {
        return try {
            if (!file.exists() || file.length() == 0L) {
                Log.w(TAG, "Downloaded file is empty or doesn't exist")
                return false
            }
            
            // Check expected size if provided
            if (request.expectedSize != null && file.length() != request.expectedSize) {
                Log.w(TAG, "Downloaded file size mismatch. Expected: ${request.expectedSize}, Got: ${file.length()}")
                return false
            }
            
            // Check file hash if provided
            if (request.expectedHash != null) {
                val actualHash = calculateFileHash(file)
                if (actualHash != request.expectedHash) {
                    Log.w(TAG, "Downloaded file hash mismatch. Expected: ${request.expectedHash}, Got: $actualHash")
                    return false
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error validating downloaded file", e)
            false
        }
    }
    
    /**
     * Calculate file hash for integrity checking
     */
    private fun calculateFileHash(file: File): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating file hash", e)
            ""
        }
    }
    
    /**
     * Update download progress
     */
    private fun updateProgress(url: String, progress: DownloadProgress) {
        val currentProgress = _downloadProgress.value.toMutableMap()
        currentProgress[url] = progress
        _downloadProgress.value = currentProgress
    }
    
    /**
     * Handle download completion
     */
    private fun onDownloadComplete(url: String) {
        activeDownloads.remove(url)
        
        // Check if queue is empty and no active downloads
        if (activeDownloads.isEmpty() && synchronized(downloadQueue) { downloadQueue.isEmpty() }) {
            isProcessingQueue.set(false)
            queueProcessingJob?.cancel()
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        // Cancel queue processing
        queueProcessingJob?.cancel()
        
        // Cancel all active downloads
        activeDownloads.values.forEach { task ->
            task.cancel()
        }
        activeDownloads.clear()
        downloadQueue.clear()
        
        Log.d(TAG, "DownloadManager cleanup completed")
    }

    /**
     * Try to fetch Content-Length using HEAD (falls back to null if unavailable)
     */
    private fun probeContentLength(urlStr: String): Long? {
        try {
            var current = urlStr
            var redirects = 0
            val maxRedirects = 5
            while (true) {
                val url = URL(current)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    instanceFollowRedirects = false
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrEmpty() || redirects++ >= maxRedirects) return null
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                    continue
                }
                val len = try { conn.getHeaderFieldLong("Content-Length", -1) } catch (_: Exception) { -1L }
                conn.disconnect()
                return if (len > 0) len else null
            }
        } catch (_: Exception) {
            return null
        }
    }
}

/**
 * Download request data class
 */
data class DownloadRequest(
    val url: String,
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    val expectedSize: Long? = null,
    val expectedHash: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Download priority levels
 */
enum class DownloadPriority {
    LOW, NORMAL, HIGH, URGENT
}

/**
 * Download progress states
 */
sealed class DownloadProgress {
    object Queued : DownloadProgress()
    data class InProgress(val progressPercent: Float, val status: String) : DownloadProgress()
    data class Completed(val filePath: String) : DownloadProgress()
    data class Failed(val error: String) : DownloadProgress()
    object Cancelled : DownloadProgress()
}

/**
 * Download result
 */
sealed class DownloadResult {
    data class Success(val filePath: String) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

/**
 * Download task
 */
private class DownloadTask(
    val request: DownloadRequest,
    val onComplete: (String) -> Unit
) {
    private val _isActive = AtomicBoolean(true)
    val isActive: Boolean get() = _isActive.get()
    
    fun cancel() {
        _isActive.set(false)
        onComplete(request.url)
    }
}

/**
 * Batch download result
 */
data class BatchDownloadResult(
    val totalRequests: Int,
    val successfulDownloads: Int,
    val failedDownloads: Int,
    val results: Map<String, Boolean>
)

/**
 * Download statistics
 */
data class DownloadStats(
    val totalDownloads: Int,
    val completedDownloads: Int,
    val failedDownloads: Int,
    val inProgressDownloads: Int,
    val queuedDownloads: Int,
    val activeDownloads: Int
)