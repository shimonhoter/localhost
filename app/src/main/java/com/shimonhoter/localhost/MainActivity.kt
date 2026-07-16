package com.shimonhoter.localhost

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private var server: LocalHttpServer? = null
    private var tempDir: File? = null
    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var pickButton: Button

    private val pickFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) openHtml(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        statusText = findViewById(R.id.statusText)
        pickButton = findViewById(R.id.pickButton)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = false
        webView.webViewClient = WebViewClient()

        pickButton.setOnClickListener {
            pickFile.launch(arrayOf("text/html"))
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) openHtml(uri)
    }

    /**
     * Serves [uri] over 127.0.0.1 and loads it in the WebView.
     * file:// uris are served straight from their parent directory (so
     * relative css/js/image links keep working). content:// uris are
     * copied once into a fresh cache folder, since the local server can
     * only serve real files on disk.
     */
    private fun openHtml(uri: Uri) {
        statusText.text = getString(R.string.status_starting)
        stopServer() // close any previously running page/server first

        try {
            val rootDir: File
            val fileName: String

            if (uri.scheme == "file") {
                val file = File(requireNotNull(uri.path))
                rootDir = file.parentFile ?: file
                fileName = file.name
            } else {
                val dir = File(cacheDir, "page_${System.currentTimeMillis()}").apply { mkdirs() }
                tempDir = dir
                val name = queryDisplayName(uri) ?: "index.html"
                val outFile = File(dir, name)
                contentResolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("לא ניתן לקרוא את הקובץ שנבחר")
                rootDir = dir
                fileName = name
            }

            val newServer = LocalHttpServer(rootDir)
            newServer.start()
            server = newServer

            webView.visibility = android.view.View.VISIBLE
            findViewById<android.view.View>(R.id.emptyState).visibility = android.view.View.GONE
            webView.loadUrl("http://127.0.0.1:${newServer.port}/$fileName")
        } catch (e: Exception) {
            statusText.text = "שגיאה בפתיחת הקובץ: ${e.message}"
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    var name = cursor.getString(idx)
                    if (name != null && !name.endsWith(".html") && !name.endsWith(".htm")) {
                        name += ".html"
                    }
                    name
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Stops the server (closing/freeing the port) and clears any temp copy. */
    private fun stopServer() {
        server?.stop()
        server = null
        tempDir?.deleteRecursively()
        tempDir = null
    }

    override fun onDestroy() {
        // Page closed -> local server and its port close immediately.
        stopServer()
        super.onDestroy()
    }
}
