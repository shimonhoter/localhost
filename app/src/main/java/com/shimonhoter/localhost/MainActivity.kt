package com.shimonhoter.localhost

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private var server: LocalHttpServer? = null
    private var tempDir: File? = null
    private var currentRootDir: File? = null
    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var pickButton: Button
    private lateinit var addressBar: android.view.View
    private lateinit var addressText: TextView

    private val pickFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) openHtml(uri)
    }

    // Holds the WebView's geolocation request while we ask the user for the runtime permission
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val requestLocationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val origin = pendingGeoOrigin
        val callback = pendingGeoCallback
        pendingGeoOrigin = null
        pendingGeoCallback = null
        if (origin != null && callback != null) {
            callback.invoke(origin, granted, false)
        }
    }

    // Holds the WebView's camera/mic request while we ask for the runtime permissions
    private var pendingMediaRequest: PermissionRequest? = null

    private val requestMediaPermissions = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val request = pendingMediaRequest
        pendingMediaRequest = null
        if (request == null) return@registerForActivityResult

        val grantedResources = request.resources.filter { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    results[Manifest.permission.CAMERA] == true
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    results[Manifest.permission.RECORD_AUDIO] == true
                else -> false
            }
        }
        if (grantedResources.isNotEmpty()) {
            request.grant(grantedResources.toTypedArray())
        } else {
            request.deny()
        }
    }

    // Holds a page's SMS-send request while we ask for the SEND_SMS runtime permission
    private var pendingSms: Pair<String, String>? = null

    private val requestSmsPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val sms = pendingSms
        pendingSms = null
        if (granted && sms != null) {
            SmsSender.send(sms.first, sms.second)
        } else if (!granted) {
            runOnUiThread { Toast.makeText(this, "לא ניתנה הרשאה לשליחת SMS", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        statusText = findViewById(R.id.statusText)
        pickButton = findViewById(R.id.pickButton)
        addressBar = findViewById(R.id.addressBar)
        addressText = findViewById(R.id.addressText)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.setGeolocationEnabled(true)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val neededPermissions = mutableListOf<String>()
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) {
                    neededPermissions += Manifest.permission.CAMERA
                }
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) {
                    neededPermissions += Manifest.permission.RECORD_AUDIO
                }
                if (neededPermissions.isEmpty()) {
                    request.deny()
                    return
                }

                val allGranted = neededPermissions.all {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                }
                if (allGranted) {
                    request.grant(request.resources)
                } else {
                    pendingMediaRequest = request
                    requestMediaPermissions.launch(neededPermissions.toTypedArray())
                }
            }
        }

        webView.setDownloadListener { url, _, _, mimeType, _ ->
            val root = currentRootDir
            if (root == null) {
                Toast.makeText(this, "לא ניתן להוריד כרגע", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            val relativePath = try {
                java.net.URLDecoder.decode(Uri.parse(url).path ?: "", "UTF-8").trimStart('/')
            } catch (e: Exception) {
                null
            }
            val sourceFile = relativePath?.let { File(root, it) }
            if (sourceFile == null || !sourceFile.exists()) {
                Toast.makeText(this, "הקובץ להורדה לא נמצא", Toast.LENGTH_SHORT).show()
                return@setDownloadListener
            }
            val saved = DownloadHelper.saveToDownloads(
                this, sourceFile, sourceFile.name, mimeType ?: "application/octet-stream"
            )
            Toast.makeText(
                this,
                if (saved) "הקובץ נשמר בתיקיית ההורדות" else "שמירת הקובץ נכשלה",
                Toast.LENGTH_SHORT
            ).show()
        }

        webView.addJavascriptInterface(
            SmsBridge { phone, message ->
                runOnUiThread {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.SEND_SMS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPermission) {
                        SmsSender.send(phone, message)
                    } else {
                        pendingSms = phone to message
                        requestSmsPermission.launch(Manifest.permission.SEND_SMS)
                    }
                }
            },
            "AndroidSms"
        )

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
            currentRootDir = rootDir

            val localUrl = "http://127.0.0.1:${newServer.port}/$fileName"

            webView.visibility = android.view.View.VISIBLE
            findViewById<android.view.View>(R.id.emptyState).visibility = android.view.View.GONE
            addressBar.visibility = android.view.View.VISIBLE
            addressText.text = localUrl
            webView.loadUrl(localUrl)
        } catch (e: Exception) {
            addressBar.visibility = android.view.View.GONE
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
        currentRootDir = null
        tempDir?.deleteRecursively()
        tempDir = null
    }

    override fun onDestroy() {
        // Page closed -> local server and its port close immediately.
        stopServer()
        super.onDestroy()
    }
}
