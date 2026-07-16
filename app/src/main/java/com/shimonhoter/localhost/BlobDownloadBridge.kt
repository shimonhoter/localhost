package com.shimonhoter.localhost

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast

class BlobDownloadBridge(private val context: Context) {

    @JavascriptInterface
    fun saveBlob(base64Data: String, filename: String, mimeType: String): Boolean {
        return try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val saved = DownloadHelper.saveBytesToDownloads(context, bytes, filename, mimeType)
            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(
                    context,
                    if (saved) "הקובץ נשמר בתיקיית ההורדות" else "שמירת הקובץ נכשלה",
                    Toast.LENGTH_SHORT
                ).show()
            }
            saved
        } catch (e: Exception) {
            false
        }
    }
}
