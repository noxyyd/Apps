package com.noxyyd.vidgrab.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import com.noxyyd.vidgrab.DownloadItem
import com.noxyyd.vidgrab.EngineState
import com.noxyyd.vidgrab.MainViewModel
import com.noxyyd.vidgrab.Mode
import com.noxyyd.vidgrab.PreviewState
import com.noxyyd.vidgrab.Quality
import com.noxyyd.vidgrab.VideoPreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var askedPermissions by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.download() }

    fun startDownload() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty() && (!askedPermissions || Build.VERSION.SDK_INT <= 28)) {
            askedPermissions = true
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            vm.download()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VidGrab", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (ui.updating) "Updating downloader…" else "Update downloader engine") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            enabled = !ui.updating && engine is EngineState.Ready,
                            onClick = { menuOpen = false; vm.updateEngine() },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear finished downloads") },
                            leadingIcon = { Icon(Icons.Default.Close, null) },
                            onClick = { menuOpen = false; vm.clearFinished() },
                        )
                        DropdownMenuItem(
                            text = { Text("yt-dlp ${ui.ytDlpVersion ?: "…"}") },
                            enabled = false,
                            onClick = {},
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { EngineBanner(engine, ui.updating) }
            item {
                UrlInput(
                    url = ui.url,
                    onChange = vm::onUrlChange,
                    onPaste = { clipboardText(context)?.let { vm.onSharedText(it) } },
                    onGo = vm::fetchPreview,
                )
            }
            item { PreviewCard(ui.preview) }
            item {
                OptionsRow(
                    mode = ui.mode,
                    quality = ui.quality,
                    onMode = vm::onModeChange,
                    onQuality = vm::onQualityChange,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = vm::fetchPreview,
                        enabled = ui.url.isNotBlank() && ui.preview !is PreviewState.Loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Visibility, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Preview")
                    }
                    Button(
                        onClick = ::startDownload,
                        enabled = ui.url.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download")
                    }
                }
            }
            item {
                Text(
                    "Tip: in Instagram, TikTok, YouTube, Facebook, X and more, tap Share → VidGrab. " +
                        "Works with 1000+ sites. Only download videos you have the right to save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (downloads.isNotEmpty()) {
                item {
                    Text(
                        "Downloads",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(downloads, key = { it.id.toString() }) { item ->
                    DownloadRow(
                        item = item,
                        onCancel = { vm.cancel(item.id) },
                        onRetry = { vm.retry(item) },
                        onOpen = { uri, mime -> open(context, uri, mime) },
                        onShare = { uri, mime -> share(context, uri, mime) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineBanner(engine: EngineState, updating: Boolean) {
    val text = when {
        engine is EngineState.Initializing -> "Preparing downloader (first launch can take a few seconds)…"
        engine is EngineState.Failed -> "Downloader failed to start: ${engine.message}"
        updating -> "Updating downloader engine…"
        else -> return
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (engine is EngineState.Failed) {
                Icon(Icons.Default.ErrorOutline, null)
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UrlInput(url: String, onChange: (String) -> Unit, onPaste: () -> Unit, onGo: () -> Unit) {
    OutlinedTextField(
        value = url,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Video link") },
        placeholder = { Text("https://www.instagram.com/reel/…") },
        trailingIcon = {
            if (url.isEmpty()) {
                IconButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, "Paste") }
            } else {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear") }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onGo() }),
    )
}

@Composable
private fun PreviewCard(state: PreviewState) {
    when (state) {
        PreviewState.Idle -> Unit
        PreviewState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Looking up video…")
        }
        is PreviewState.Error -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Text(state.message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
        is PreviewState.Loaded -> LoadedPreview(state.preview)
    }
}

@Composable
private fun LoadedPreview(p: VideoPreview) {
    Card {
        Row(Modifier.padding(12.dp)) {
            Box(
                Modifier.size(width = 120.dp, height = 90.dp).clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = p.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(Icons.Default.PlayArrow, null, tint = androidx.compose.ui.graphics.Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(p.site, p.uploader, formatDuration(p.durationSec)).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsRow(mode: Mode, quality: Quality, onMode: (Mode) -> Unit, onQuality: (Quality) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                    icon = {
                        Icon(if (m == Mode.VIDEO) Icons.Default.Videocam else Icons.Default.MusicNote, null)
                    },
                ) { Text(if (m == Mode.VIDEO) "Video (MP4)" else "Audio (MP3)") }
            }
        }
        if (mode == Mode.VIDEO) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Quality.entries.forEach { q ->
                    FilterChip(selected = quality == q, onClick = { onQuality(q) }, label = { Text(q.label) })
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (Uri, String) -> Unit,
    onShare: (Uri, String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            when (item.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    Text("Waiting…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    ActionRow { TextButton(onClick = onCancel) { Text("Cancel") } }
                }
                WorkInfo.State.RUNNING -> {
                    val eta = if (item.etaSec > 0) " · ${formatDuration(item.etaSec.toInt())} left" else ""
                    val pct = if (item.progress > 0f) "${item.progress.toInt()}% · " else ""
                    Text("$pct${item.stage}$eta", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    if (item.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { (item.progress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    ActionRow { TextButton(onClick = onCancel) { Text("Cancel") } }
                }
                WorkInfo.State.SUCCEEDED -> {
                    val count = item.files.size
                    Text(
                        if (count > 1) "Saved $count files to your gallery" else "Saved to your gallery",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    item.files.firstOrNull()?.let { (uri, mime) ->
                        ActionRow {
                            TextButton(onClick = { onShare(uri, mime) }) {
                                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Share")
                            }
                            TextButton(onClick = { onOpen(uri, mime) }) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Open")
                            }
                        }
                    }
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    val msg = if (item.state == WorkInfo.State.CANCELLED) "Cancelled" else item.error ?: "Failed"
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (item.url.isNotEmpty()) {
                        ActionRow { TextButton(onClick = onRetry) { Text("Try again") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { content() }
}

private fun formatDuration(totalSec: Int): String? {
    if (totalSec <= 0) return null
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun open(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_SEND).setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share"))
}
