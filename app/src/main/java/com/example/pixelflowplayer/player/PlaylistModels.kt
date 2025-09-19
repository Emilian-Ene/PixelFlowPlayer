package com.example.pixelflowplayer.player

data class Playlist(
    val items: List<PlaylistItem>,
    val orientation: String? = "Landscape" // Default to Landscape
) {
    /**
     * Compares this playlist to another, checking the essential content and presentation properties.
     * It ignores differences in local vs. remote URLs for the items.
     */
    fun isContentEqualTo(other: Playlist?): Boolean {
        if (other == null) return false

        // Check if overall orientation is the same
        if (this.orientation != other.orientation) return false
        if (this.items.size != other.items.size) return false

        // Compare each item by its server URL, duration, and display mode
        for (i in this.items.indices) {
            val thisItem = this.items[i]
            val otherItem = other.items[i]

            val thisItemServerUrl = thisItem.url.substringAfterLast('/')
            val otherItemServerUrl = otherItem.url.substringAfterLast('/')

            if (thisItemServerUrl != otherItemServerUrl ||
                thisItem.duration != otherItem.duration ||
                thisItem.displayMode != otherItem.displayMode) {
                return false // Found a difference
            }
        }

        return true // All items and properties are identical
    }
}

data class PlaylistItem(
    val type: String,
    val url: String,
    val duration: Int,
    val displayMode: String? = "contain" // Default to contain
)