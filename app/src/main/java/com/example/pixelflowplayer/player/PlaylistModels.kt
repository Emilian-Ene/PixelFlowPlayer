package com.example.pixelflowplayer.player

data class Playlist(
    val items: List<PlaylistItem>,
    val orientation: String = "Landscape", // Canvas layout
    val transitionType: String = "Cut"      // Cut | Fade | Slide (future-proof)
) {
    /**
     * Compares this playlist to another, checking essential content and presentation properties.
     * - Ignores local vs remote URL prefixes by comparing only the filename.
     * - Strips optional cache hash prefixes like "<md5>_" from local file names before compare.
     * - Compares orientation and transitionType.
     * - Compares each item's type, url filename, duration, displayMode, and optional dimensions.
     */
    fun isContentEqualTo(other: Playlist?): Boolean {
        if (other == null) return false

        if (this.orientation != other.orientation) return false
        if (this.transitionType != other.transitionType) return false
        if (this.items.size != other.items.size) return false

        fun normalize(name: String): String {
            val base = name.substringAfterLast('/')
            // Remove "file://" path and Windows-style path separators defensively
            val clean = base.substringAfterLast('\\')
            // Strip md5/hash prefix if present (32 hex chars + underscore)
            val regex = Regex("^[0-9a-fA-F]{32}_")
            return clean.replace(regex, "")
        }

        for (i in this.items.indices) {
            val a = this.items[i]
            val b = other.items[i]

            val aName = normalize(a.url)
            val bName = normalize(b.url)

            val aMode = a.displayMode ?: "contain"
            val bMode = b.displayMode ?: "contain"

            if (
                a.type.lowercase() != b.type.lowercase() ||
                aName != bName ||
                a.duration != b.duration ||
                aMode != bMode ||
                a.width != b.width ||
                a.height != b.height ||
                ((a.aspectRatio ?: 0.0) != (b.aspectRatio ?: 0.0))
            ) {
                return false
            }
        }
        return true
    }
}

data class PlaylistItem(
    val type: String,                 // "image" | "video"
    val url: String,                  // absolute or local file URI
    val duration: Int,                // 0 for videos
    val displayMode: String? = "contain", // "contain" | "cover" | "fill"
    val width: Int = 0,
    val height: Int = 0,
    val aspectRatio: Double? = null   // optional: width/height when available
)