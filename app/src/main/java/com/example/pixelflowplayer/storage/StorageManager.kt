package com.example.pixelflowplayer.storage

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Storage management for offline media cache.
 */
class StorageManager(private val context: Context) {
    companion object {
        private const val TAG = "StorageManager"
        private const val DEFAULT_MAX_CACHE_SIZE_MB = 2048L // 2GB
        private const val MIN_FREE_SPACE_MB = 128L          // keep 128MB free
        private const val CACHE_CLEANUP_THRESHOLD = 0.85f
        private const val MAX_FILE_AGE_DAYS = 30
    }

    private val cacheDirectory: File = File(context.filesDir, "media_cache")
    private val metadataFile: File = File(context.filesDir, "cache_metadata.json")
    private val maxCacheSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE_MB * 1024 * 1024
    private val minFreeSpaceBytes: Long = MIN_FREE_SPACE_MB * 1024 * 1024

    init {
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs()
            Log.d(TAG, "Created cache dir: ${cacheDirectory.absolutePath}")
        }
    }

    fun getAvailableSpace(): Long = try {
        val s = StatFs(cacheDirectory.absolutePath)
        s.availableBlocksLong * s.blockSizeLong
    } catch (e: Exception) {
        Log.e(TAG, "getAvailableSpace failed", e)
        0L
    }

    suspend fun getCurrentCacheSize(): Long = withContext(Dispatchers.IO) {
        calculateDirectorySize(cacheDirectory)
    }

    suspend fun hasSpaceForFile(sizeBytes: Long): Boolean {
        val available = getAvailableSpace()
        val current = getCurrentCacheSize()
        val wouldExceedCache = current + sizeBytes > maxCacheSizeBytes
        val wouldExceedFree = sizeBytes + minFreeSpaceBytes > available
        if (wouldExceedCache || wouldExceedFree) {
            Log.d(TAG, "Insufficient space. cache=${current/1024/1024}MB avail=${available/1024/1024}MB need=${sizeBytes/1024/1024}MB")
            return false
        }
        return true
    }

    /** Ensure there is enough space, trying cleanup if needed. */
    suspend fun ensureSpaceForBytes(requiredBytes: Long): Boolean = withContext(Dispatchers.IO) {
        if (hasSpaceForFile(requiredBytes)) return@withContext true
        performCleanup(forceCleanup = true)
        if (hasSpaceForFile(requiredBytes)) return@withContext true
        // delete oldest files one by one
        val files = cacheDirectory.listFiles()?.toMutableList() ?: mutableListOf()
        files.sortWith(compareBy<File> { getFileLastAccessed(it) }.thenBy { it.lastModified() })
        for (f in files) {
            try {
                val sz = f.length()
                if (f.delete()) Log.d(TAG, "Deleted old cache file: ${f.name} (${sz/1024}KB)")
            } catch (_: Exception) {}
            if (hasSpaceForFile(requiredBytes)) return@withContext true
        }
        hasSpaceForFile(requiredBytes)
    }

    /** Cleanup based on age/space thresholds. */
    suspend fun performCleanup(forceCleanup: Boolean = false): CleanupResult = withContext(Dispatchers.IO) {
        val currentSize = getCurrentCacheSize()
        val shouldCleanup = forceCleanup ||
            (currentSize.toFloat() / maxCacheSizeBytes) > CACHE_CLEANUP_THRESHOLD ||
            getAvailableSpace() < minFreeSpaceBytes
        if (!shouldCleanup) return@withContext CleanupResult(0, 0L, "No cleanup needed")

        val files = cacheDirectory.listFiles()?.toList() ?: emptyList()
        val sorted = files.sortedWith(compareBy<File> { getFileLastAccessed(it) }.thenBy { it.lastModified() })
        var deleted = 0
        var freed = 0L
        val targetSize = (maxCacheSizeBytes * 0.7).toLong()
        for (f in sorted) {
            if (getCurrentCacheSize() <= targetSize && getAvailableSpace() >= minFreeSpaceBytes) break
            if (shouldDeleteFile(f)) {
                val sz = f.length()
                if (f.delete()) { deleted++; freed += sz; Log.d(TAG, "Deleted cached: ${f.name}") }
            }
        }
        Log.d(TAG, "Cleanup done. Deleted $deleted files (${freed/1024/1024}MB)")
        CleanupResult(deleted, freed, "Cleanup successful")
    }

    fun getCacheFile(url: String): File = File(cacheDirectory, generateCacheFileName(url))

    suspend fun markFileAccessed(file: File) = withContext(Dispatchers.IO) {
        if (file.exists()) updateFileMetadata(file.name, System.currentTimeMillis())
    }

    suspend fun validateCacheFile(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) return@withContext false
            val header = ByteArray(12)
            val read = try { file.inputStream().use { it.read(header) } } catch (_: Exception) { 0 }
            val isJpeg = read >= 2 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
            val isPng = read >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() && header[4] == 0x0D.toByte() && header[5] == 0x0A.toByte() && header[6] == 0x1A.toByte() && header[7] == 0x0A.toByte()
            val isWebp = read >= 12 && header.copyOfRange(0,4).contentEquals(byteArrayOf(0x52,0x49,0x46,0x46)) && header.copyOfRange(8,12).contentEquals(byteArrayOf(0x57,0x45,0x42,0x50))
            val isMp4 = read >= 12 && header.copyOfRange(4,8).contentEquals(byteArrayOf(0x66,0x74,0x79,0x70))
            val looksLikeMedia = isJpeg || isPng || isWebp || isMp4
            if (!looksLikeMedia) return@withContext false
            file.canRead() && file.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "validateCacheFile error", e); false
        }
    }

    suspend fun getCacheStats(): CacheStats = withContext(Dispatchers.IO) {
        val files = cacheDirectory.listFiles()?.toList() ?: emptyList()
        val total = calculateDirectorySize(cacheDirectory)
        val oldest = files.minByOrNull { it.lastModified() }
        val newest = files.maxByOrNull { it.lastModified() }
        CacheStats(
            totalSizeBytes = total,
            fileCount = files.size,
            maxSizeBytes = maxCacheSizeBytes,
            availableSpaceBytes = getAvailableSpace(),
            oldestFileAge = oldest?.let { (System.currentTimeMillis() - it.lastModified()) / (24*60*60*1000) } ?: 0,
            newestFileAge = newest?.let { (System.currentTimeMillis() - it.lastModified()) / (24*60*60*1000) } ?: 0
        )
    }

    /** Delete everything from media_cache. */
    suspend fun clearCache(): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        try {
            if (cacheDirectory.exists()) {
                cacheDirectory.walkBottomUp().forEach { f -> if (f != cacheDirectory && f.delete()) deleted++ }
                cacheDirectory.delete()
                cacheDirectory.mkdirs()
            }
            if (metadataFile.exists()) metadataFile.delete()
            Log.d(TAG, "Cleared media cache. Deleted $deleted entries")
        } catch (e: Exception) { Log.e(TAG, "clearCache failed", e) }
        deleted
    }

    /** Keep only urlsToKeep. */
    suspend fun cleanupCacheExcept(urlsToKeep: Set<String>): CleanupResult = withContext(Dispatchers.IO) {
        val keepNames = urlsToKeep.map { getCacheFile(it).name }.toSet()
        val files = cacheDirectory.listFiles()?.toList() ?: emptyList()
        var deleted = 0
        var freed = 0L
        files.forEach { f ->
            if (f.isFile && f.name !in keepNames) {
                val sz = f.length()
                if (f.delete()) {
                    deleted++; freed += sz
                    val n = f.name.lowercase()
                    val isVideo = n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv") || n.endsWith(".mov") || n.endsWith(".avi")
                    val isImage = n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
                    when {
                        isVideo -> Log.d(TAG, "🗑️ Video deleted: ${f.name}")
                        isImage -> Log.d(TAG, "🗑️ Image deleted: ${f.name}")
                        else -> Log.d(TAG, "🗑️ Media deleted: ${f.name}")
                    }
                }
            }
        }
        CleanupResult(deleted, freed, "Removed unused cache files")
    }

    private fun calculateDirectorySize(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun generateCacheFileName(url: String): String {
        val normalized = url.trim().replace("\\\\", "/").removeSuffix("/")
        val lastSegment = normalized.substringAfterLast('/')
        val cleanLastSegment = lastSegment.substringBefore('?').substringBefore('#')
        val extension = cleanLastSegment.substringAfterLast('.', "").lowercase()
        val baseName = cleanLastSegment.substringBeforeLast('.', cleanLastSegment)
        val md = java.security.MessageDigest.getInstance("MD5")
        val stableHash = md.digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
        return if (extension.isNotEmpty()) "${stableHash}_${baseName}.$extension" else "${stableHash}_${baseName}"
    }

    private fun shouldDeleteFile(file: File): Boolean {
        val age = System.currentTimeMillis() - file.lastModified()
        val maxAge = MAX_FILE_AGE_DAYS * 24 * 60 * 60 * 1000L
        val lastAccessed = getFileLastAccessed(file)
        val accessAge = System.currentTimeMillis() - lastAccessed
        return age > maxAge || accessAge > maxAge
    }

    private fun getFileLastAccessed(file: File): Long = file.lastModified()

    private suspend fun updateFileMetadata(fileName: String, accessTime: Long) {
        // Placeholder for metadata storage (not required for current functionality)
        Log.d(TAG, "Updated access time for $fileName: $accessTime")
    }
}

/** Result of cache cleanup operation */
data class CleanupResult(
    val deletedFiles: Int,
    val deletedSizeBytes: Long,
    val message: String
)

/** Cache statistics */
data class CacheStats(
    val totalSizeBytes: Long,
    val fileCount: Int,
    val maxSizeBytes: Long,
    val availableSpaceBytes: Long,
    val oldestFileAge: Long,
    val newestFileAge: Long
) {
    val usagePercentage: Float
        get() = if (maxSizeBytes > 0) (totalSizeBytes.toFloat() / maxSizeBytes) * 100 else 0f
}