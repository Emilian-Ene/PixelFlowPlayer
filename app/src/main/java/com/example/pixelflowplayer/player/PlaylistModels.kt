package com.example.pixelflowplayer.player

data class Playlist(
    val items: List<PlaylistItem>
) {
    // --- THIS IS THE NEW FUNCTION ---
    /**
     * Compares this playlist to another, checking only the essential content.
     * It ignores differences in local vs. remote URLs.
     */
    fun isContentEqualTo(other: Playlist?): Boolean {
        if (other == null) return false
        if (this.items.size != other.items.size) return false

        // Compare each item by its server URL and duration
        for (i in this.items.indices) {
            val thisItemServerUrl = this.items[i].url.substringAfterLast('/')
            val otherItemServerUrl = other.items[i].url.substringAfterLast('/')

            if (thisItemServerUrl != otherItemServerUrl || this.items[i].duration != other.items[i].duration) {
                return false // Found a difference
            }
        }

        return true // All items are identical
    }
}

data class PlaylistItem(
    val type: String,
    val url: String,
    val duration: Int
)