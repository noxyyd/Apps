package com.noxyyd.vidgrab

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlin.concurrent.thread

class VidGrabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DownloadWorker.CHANNEL_ID,
                getString(R.string.channel_downloads),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        // Unpacking Python + yt-dlp takes a few seconds on first launch; do it in the background,
        // then make sure yt-dlp is recent (sites like Instagram and TikTok change all the time).
        thread(name = "engine-init") {
            runCatching {
                Engine.ensureReady(this)
                Engine.updateIfStale(this)
            }
        }
    }
}
