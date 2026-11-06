package com.noxyyd.vidgrab

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File

enum class Mode { VIDEO, AUDIO }

/** Video quality cap. `null` height = best available. */
enum class Quality(val label: String, val height: Int?) {
    BEST("Best", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360),
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "vidgrab"
        const val KEY_URL = "url"
        const val KEY_TITLE = "title"
        const val KEY_MODE = "mode"
        const val KEY_QUALITY = "quality"

        const val KEY_PROGRESS = "progress"
        const val KEY_ETA = "eta"
        const val KEY_STAGE = "stage"

        const val KEY_URIS = "uris"
        const val KEY_MIMES = "mimes"
        const val KEY_NAMES = "names"
        const val KEY_ERROR = "error"

        const val CHANNEL_ID = "downloads"
    }

    private val notificationId = id.hashCode()
    private val title get() = inputData.getString(KEY_TITLE)?.takeIf { it.isNotBlank() }
        ?: inputData.getString(KEY_URL).orEmpty()

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure(workDataOf(KEY_ERROR to "No link"))
        val mode = Mode.valueOf(inputData.getString(KEY_MODE) ?: Mode.VIDEO.name)
        val quality = Quality.valueOf(inputData.getString(KEY_QUALITY) ?: Quality.BEST.name)

        runCatching { setForeground(foregroundInfo(progressNotification(0f, "Starting…"))) }

        val workDir = File(applicationContext.cacheDir, "downloads/$id").apply { mkdirs() }
        return try {
            runInterruptible(Dispatchers.IO) { Engine.ensureReady(applicationContext) }
            runYtDlp(buildRequest(url, workDir, mode, quality))

            setProgress(workDataOf(KEY_PROGRESS to 100f, KEY_STAGE to "Saving…"))
            val files = workDir.listFiles()
                .orEmpty()
                .filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                .sortedBy { it.name }
            if (files.isEmpty()) error("Nothing was downloaded")

            val saved = files.map { MediaSaver.save(applicationContext, it) }
            notifyFinished(success = true, text = saved.joinToString { it.name }, saved.first())
            Result.success(
                workDataOf(
                    KEY_URIS to saved.map { it.uri.toString() }.toTypedArray(),
                    KEY_MIMES to saved.map { it.mime }.toTypedArray(),
                    KEY_NAMES to saved.map { it.name }.toTypedArray(),
                )
            )
        } catch (t: Throwable) {
            if (isStopped) return Result.failure(workDataOf(KEY_ERROR to "Cancelled"))
            val message = friendlyError(t)
            notifyFinished(success = false, text = message, file = null)
            Result.failure(workDataOf(KEY_ERROR to message))
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun buildRequest(url: String, dir: File, mode: Mode, quality: Quality) =
        YoutubeDLRequest(url).apply {
            addOption("--no-playlist")      // a video link inside a playlist → just that video
            addOption("--no-mtime")
            addOption("--newline")
            addOption("-o", "${dir.absolutePath}/%(title).70B [%(id)s].%(ext)s")
            when (mode) {
                Mode.AUDIO -> {
                    addOption("-f", "ba/b")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                }
                Mode.VIDEO -> {
                    addOption("-f", "bv*+ba/b")
                    // Prefer the requested resolution, then MP4/M4A so it plays in every gallery app.
                    val res = quality.height?.let { "res:$it" } ?: "res"
                    addOption("-S", "$res,ext:mp4:m4a")
                    addOption("--merge-output-format", "mp4")
                }
            }
        }

    /** Runs yt-dlp, reporting progress, and kills the process if the work is cancelled. */
    private suspend fun runYtDlp(request: YoutubeDLRequest) = coroutineScope {
        val processId = id.toString()
        var finished = false
        val killer = launch {
            try {
                awaitCancellation()
            } finally {
                if (!finished) YoutubeDL.getInstance().destroyProcessById(processId)
            }
        }
        var lastUpdate = 0L
        try {
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate < 400) return@execute
                    lastUpdate = now
                    val stage = stageOf(line)
                    setProgressAsync(workDataOf(KEY_PROGRESS to progress, KEY_ETA to eta, KEY_STAGE to stage))
                    notify(progressNotification(progress, stage))
                }
            }
        } finally {
            finished = true
            killer.cancel()
        }
    }

    private fun stageOf(line: String): String = when {
        line.startsWith("[Merger]") -> "Merging video and audio…"
        line.startsWith("[ExtractAudio]") -> "Converting to MP3…"
        line.startsWith("[download]") -> "Downloading…"
        else -> "Working…"
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(progress: Float, stage: String): Notification {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(stage)
            .setProgress(100, progress.toInt().coerceIn(0, 100), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun foregroundInfo(notification: Notification) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun notify(notification: Notification) {
        if (canNotify()) NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
    }

    @Suppress("MissingPermission")
    private fun notifyFinished(success: Boolean, text: String, file: SavedFile?) {
        if (!canNotify()) return
        val tap = file?.let {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(it.uri, it.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(applicationContext, notificationId, view, PendingIntent.FLAG_IMMUTABLE)
        } ?: openAppIntent()
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (success) "Downloaded: $title" else "Download failed: $title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        // Different id from the foreground notification, which is removed when the work ends.
        NotificationManagerCompat.from(applicationContext).notify(notificationId + 1, n)
    }
}
