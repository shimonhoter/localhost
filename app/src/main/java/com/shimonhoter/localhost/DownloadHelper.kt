package com.shimonhoter.localhost

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * Saves a file that already sits on disk under the app's local-server root
 * into the device's public Downloads folder, so pages served over
 * 127.0.0.1 can trigger a real "download" the user can find afterwards.
 */
object DownloadHelper {

    fun saveToDownloads(context: Context, sourceFile: File, displayName: String, mimeType: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input -> input.copyTo(out) }
                } ?: return false
                true
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                val outFile = File(downloadsDir, displayName)
                FileInputStream(sourceFile).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
