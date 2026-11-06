package com.noxyyd.vidgrab

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class VideoPreview(
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val durationSec: Int,
    val site: String?,
)

sealed interface PreviewState {
    data object Idle : PreviewState
    data object Loading : PreviewState
    data class Loaded(val preview: VideoPreview) : PreviewState
    data class Error(val message: String) : PreviewState
}

data class DownloadItem(
    val id: UUID,
    val title: String,
    val url: String,
    val createdAt: Long,
    val state: WorkInfo.State,
    val progress: Float,
    val etaSec: Long,
    val stage: String,
    val files: List<Pair<Uri, String>>, // uri to mime
    val error: String?,
)

data class UiState(
    val url: String = "",
    val mode: Mode = Mode.VIDEO,
    val quality: Quality = Quality.BEST,
    val preview: PreviewState = PreviewState.Idle,
    val ytDlpVersion: String? = null,
    val updating: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val workManager = WorkManager.getInstance(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val engine: StateFlow<EngineState> = Engine.state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val downloads: StateFlow<List<DownloadItem>> = workManager
        .getWorkInfosByTagFlow(DownloadWorker.TAG)
        .map { infos -> infos.map(::toItem).sortedByDescending { it.createdAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            Engine.state.collect { state ->
                if (state is EngineState.Ready) {
                    val v = withContext(Dispatchers.IO) { Engine.version(getApplication()) }
                    _ui.update { it.copy(ytDlpVersion = v) }
                }
            }
        }
    }

    fun onUrlChange(value: String) {
        _ui.update { it.copy(url = value, preview = PreviewState.Idle) }
        previewJob?.cancel()
    }

    fun onModeChange(mode: Mode) = _ui.update { it.copy(mode = mode) }
    fun onQualityChange(q: Quality) = _ui.update { it.copy(quality = q) }

    /** Text shared from another app, e.g. "Check this out! https://www.tiktok.com/@x/video/123". */
    fun onSharedText(text: String?) {
        val url = extractUrl(text) ?: run {
            if (!text.isNullOrBlank()) _messages.tryEmit("No link found in the shared text")
            return
        }
        onUrlChange(url)
        fetchPreview()
    }

    fun fetchPreview() {
        val url = extractUrl(_ui.value.url) ?: run {
            _messages.tryEmit("Paste a valid link first")
            return
        }
        previewJob?.cancel()
        _ui.update { it.copy(url = url, preview = PreviewState.Loading) }
        previewJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Engine.ensureReady(getApplication())
                    val req = YoutubeDLRequest(url)
                        .addOption("--no-playlist")
                        .addOption("--playlist-items", "1")
                    val info = YoutubeDL.getInstance().getInfo(req)
                    VideoPreview(
                        url = url,
                        title = info.title ?: info.fulltitle ?: url,
                        uploader = info.uploader,
                        thumbnail = info.thumbnail,
                        durationSec = info.duration,
                        site = info.extractorKey,
                    )
                }
            }
            _ui.update { state ->
                if (state.url != url) return@update state
                state.copy(
                    preview = result.fold(
                        onSuccess = { PreviewState.Loaded(it) },
                        onFailure = { PreviewState.Error(friendlyError(it)) },
                    )
                )
            }
        }
    }

    fun download() {
        val state = _ui.value
        val url = extractUrl(state.url) ?: run {
            _messages.tryEmit("Paste a valid link first")
            return
        }
        val title = (state.preview as? PreviewState.Loaded)?.preview?.title ?: url
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to url,
                    DownloadWorker.KEY_TITLE to title,
                    DownloadWorker.KEY_MODE to state.mode.name,
                    DownloadWorker.KEY_QUALITY to state.quality.name,
                )
            )
            .addTag(DownloadWorker.TAG)
            // WorkInfo doesn't carry input data, so stash what the list needs in tags.
            .addTag(TAG_TITLE + title.take(200))
            .addTag(TAG_URL + url)
            .addTag(TAG_TIME + System.currentTimeMillis())
            .build()
        workManager.enqueue(request)
        _messages.tryEmit("Download started")
        _ui.update { it.copy(url = "", preview = PreviewState.Idle) }
    }

    fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    fun clearFinished() {
        workManager.pruneWork()
    }

    fun retry(item: DownloadItem) {
        _ui.update { it.copy(url = item.url, preview = PreviewState.Idle) }
        fetchPreview()
    }

    fun updateEngine() {
        if (_ui.value.updating) return
        _ui.update { it.copy(updating = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val updated = Engine.update(getApplication())
                    updated to Engine.version(getApplication())
                }
            }
            result.fold(
                onSuccess = { (updated, version) ->
                    _ui.update { it.copy(updating = false, ytDlpVersion = version) }
                    _messages.tryEmit(if (updated) "Updated to $version" else "Already up to date ($version)")
                },
                onFailure = {
                    _ui.update { s -> s.copy(updating = false) }
                    _messages.tryEmit("Update failed: ${friendlyError(it)}")
                },
            )
        }
    }

    private fun toItem(info: WorkInfo): DownloadItem {
        fun tag(prefix: String) = info.tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        val out = info.outputData
        val uris = out.getStringArray(DownloadWorker.KEY_URIS).orEmpty()
        val mimes = out.getStringArray(DownloadWorker.KEY_MIMES).orEmpty()
        return DownloadItem(
            id = info.id,
            title = tag(TAG_TITLE) ?: "Video",
            url = tag(TAG_URL).orEmpty(),
            createdAt = tag(TAG_TIME)?.toLongOrNull() ?: 0L,
            state = info.state,
            progress = info.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f),
            etaSec = info.progress.getLong(DownloadWorker.KEY_ETA, -1L),
            stage = info.progress.getString(DownloadWorker.KEY_STAGE) ?: "Waiting…",
            files = uris.mapIndexed { i, u -> Uri.parse(u) to (mimes.getOrNull(i) ?: "*/*") },
            error = out.getString(DownloadWorker.KEY_ERROR),
        )
    }

    companion object {
        private const val TAG_TITLE = "title:"
        private const val TAG_URL = "url:"
        private const val TAG_TIME = "time:"

        private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

        fun extractUrl(text: String?): String? =
            text?.let { URL_REGEX.find(it)?.value?.trimEnd('.', ',', ')', '!', '?') }
    }
}
