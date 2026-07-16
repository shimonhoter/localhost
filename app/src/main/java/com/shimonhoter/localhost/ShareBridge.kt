package com.shimonhoter.localhost

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import java.io.File

class ShareBridge(private val context: Context) {

    @JavascriptInterface
    fun shareFile(base64Data: String, filename: String, mimeType: String, title: String, text: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val shareDir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(shareDir, filename)
            file.writeBytes(bytes)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            // silently ignore — matches navigator.share's fire-and-forget UX
        }
    }

    @JavascriptInterface
    fun shareText(title: String, text: String, url: String) {
        try {
            val body = listOf(text, url).filter { it.isNotBlank() }.joinToString("\n")
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            val chooser = Intent.createChooser(sendIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            // silently ignore
        }
    }
}
