package com.example.pixelflowplayer.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import com.example.pixelflowplayer.MainActivity

@UnstableApi
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // We only care about the BOOT_COMPLETED event
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Create an Intent to launch our MainActivity
            val launchIntent = Intent(context, MainActivity::class.java)
            // This flag is CRITICAL. It allows the app to start from a background context.
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Tell the context to start our activity
            context.startActivity(launchIntent)
        }
    }
}