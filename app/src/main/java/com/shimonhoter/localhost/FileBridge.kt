package com.shimonhoter.localhost

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FileBridge(
    private val context: Context,
    private val hasFileAccess: () -> Boolean,
    private val requestFileAccess: () -> Unit
) {
    @JavascriptInterface
    fun listFiles(dirPath: String): String {
        if (!hasFileAccess()) {
            requestFileAccess()
            return errorJson("permission_required")
        }
        val dir = File(dirPath)
        if (!dir.isDirectory) return errorJson("not_a_directory")
        val array = JSONArray()
        dir.listFiles()?.forEach { f ->
            array.put(JSONObject().apply {
                put("name", f.name)
                put("path", f.absolutePath)
                put("isDirectory", f.isDirectory)
                put("size", f.length())
            })
        }
        return array.toString()
    }

    @JavascriptInterface
    fun readFileText(path: String): String {
        if (!hasFileAccess()) {
            requestFileAccess()
            return errorJson("permission_required")
        }
        return try {
            File(path).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            errorJson("read_failed: ${e.message}")
        }
    }

    @JavascriptInterface
    fun writeFileText(path: String, content: String): Boolean {
        if (!hasFileAccess()) {
            requestFileAccess()
            return false
        }
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun deleteFile(path: String): Boolean {
        if (!hasFileAccess()) {
            requestFileAccess()
            return false
        }
        return try {
            File(path).deleteRecursively()
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun openFile(path: String): Boolean {
        if (!hasFileAccess()) {
            requestFileAccess()
            return false
        }
        return try {
            val file = File(path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mimeType = context.contentResolver.getType(uri) ?: "*/*"
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(viewIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun errorJson(message: String): String =
        JSONObject().apply { put("error", message) }.toString()
}
