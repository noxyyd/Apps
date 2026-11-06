package com.noxyyd.vidgrab

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface EngineState {
    data object Initializing : EngineState
    data object Ready : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * Wraps yt-dlp (via youtubedl-android) + ffmpeg. yt-dlp is what makes "every site" possible:
 * it knows how to extract videos from Instagram, TikTok, YouTube, Facebook, X, Reddit, Vimeo,
 * Pinterest, Twitch, Dailymotion, and well over a thousand others.
 */
object Engine {
    private val _state = MutableStateFlow<EngineState>(EngineState.Initializing)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var ready = false

    /** Unpacks Python/yt-dlp/ffmpeg on first run. Blocking; call off the main thread. */
    @Synchronized
    fun ensureReady(context: Context) {
        if (ready) return
        try {
            val app = context.applicationContext
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            ready = true
            _state.value = EngineState.Ready
        } catch (t: Throwable) {
            _state.value = EngineState.Failed(t.message ?: t.javaClass.simpleName)
            throw t
        }
    }

    private const val PREFS = "engine"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Sites change often, so keep yt-dlp fresh. Returns true if a new version was installed. */
    fun update(context: Context): Boolean {
        ensureReady(context)
        val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        return status == YoutubeDL.UpdateStatus.DONE
    }

    fun updateIfStale(context: Context) {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_UPDATE, 0)
        if (System.currentTimeMillis() - last > UPDATE_INTERVAL_MS) {
            runCatching { update(context) }
        }
    }

    fun version(context: Context): String? = runCatching {
        YoutubeDL.getInstance().version(context)
    }.getOrNull()
}

/** Turns yt-dlp's long stderr dump into one readable sentence. */
fun friendlyError(t: Throwable): String {
    val raw = t.message ?: t.javaClass.simpleName
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val line = lines.lastOrNull { it.startsWith("ERROR") } ?: lines.lastOrNull() ?: raw
    val msg = line.removePrefix("ERROR:").trim().replace(Regex("^\\[[^]]+]\\s*"), "")
    val lower = raw.lowercase()
    val hint = when {
        "login" in lower || "cookies" in lower || "private" in lower ->
            " — the post may be private or require being logged in."
        "unsupported url" in lower -> " — this link isn't a supported video page."
        "no video formats" in lower -> " — this post has no video (photo-only posts aren't supported)."
        "unable to download webpage" in lower || "network" in lower -> " — check your internet connection."
        else -> ""
    }
    return (msg.take(240) + hint)
}
