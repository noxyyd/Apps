package com.noxyyd.vidgrab

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

data class SavedFile(val uri: Uri, val mime: String, val name: String)

/**
 * Moves a finished download into public storage so it shows up in the Gallery / Music apps:
 * videos → Movies/VidGrab, audio → Music/VidGrab, images → Pictures/VidGrab, anything else →
 * Download/VidGrab.
 */
object MediaSaver {
    private const val FOLDER = "VidGrab"

    fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: when (file.extension.lowercase()) {
                "mkv" -> "video/x-matroska"
                "opus" -> "audio/ogg"
                "m4a" -> "audio/mp4"
                else -> "application/octet-stream"
            }

    fun save(context: Context, file: File): SavedFile {
        val mime = mimeOf(file)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(context, file, mime)
        } else {
            saveLegacy(context, file, mime)
        }
    }

    private fun saveScoped(context: Context, file: File, mime: String): SavedFile {
        val (collection, dir) = when {
            mime.startsWith("video/") ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            mime.startsWith("image/") ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            else ->
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: error("Could not create ${file.name} in $dir")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("Could not write ${file.name}")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        file.delete()
        return SavedFile(uri, mime, file.name)
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File, mime: String): SavedFile {
        val type = when {
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(type), FOLDER).apply { mkdirs() }
        var target = File(dir, file.name)
        var n = 1
        while (target.exists()) {
            target = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}")
            n++
        }
        file.copyTo(target)
        file.delete()
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        return SavedFile(uri, mime, target.name)
    }
}
