package com.example.pixelflowplayer.player

/**
 * Represents a full playlist, which is just a list of media items.
 * This structure must match the 'playlist' object in the JSON from your server.
 */
data class Playlist(
    val items: List<PlaylistItem>
)

/**
 * Represents a single item (an image or a video) within a playlist.
 * This structure must match the objects inside the 'items' array in the JSON.
 */
data class PlaylistItem(
    val type: String,
    val url: String,
    val duration: Int
)