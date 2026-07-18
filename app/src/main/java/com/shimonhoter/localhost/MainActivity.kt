package com.shimonhoter.localhost

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebChromeClient.CustomViewCallback
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
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
    private lateinit var refreshButton: TextView
    private lateinit var autoRefreshButton: TextView
    private lateinit var printButton: TextView
    private lateinit var fullscreenContainer: android.widget.FrameLayout

    // HTML5 fullscreen video state
    private var customView: android.view.View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val requestNotificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* no-op: just needed so the OS allows notifications at all on API 33+ */ }

    private val autoRefreshHandler = Handler(Looper.getMainLooper())
    private var autoRefreshIntervalMs: Long = 0L // 0 = off
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (autoRefreshIntervalMs > 0) {
                webView.reload()
                autoRefreshHandler.postDelayed(this, autoRefreshIntervalMs)
            }
        }
    }

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

    // "All files access" settings screen (API 30+) — no direct result payload,
    // the page just needs to retry its call after coming back.
    private val requestAllFilesAccess = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { /* user returns from Settings; next bridge call re-checks access */ }

    // Legacy (pre-API 30) storage permissions
    private val requestLegacyStoragePermissions = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { /* next bridge call re-checks access */ }

    private val requestContactsPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* next AndroidContacts.getContacts() call re-checks access */ }

    // Holds the <input type="file"> callback while the system file picker is open
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileChooserCallback
        pendingFileChooserCallback = null
        val uris = FileChooserParams.parseResult(result.resultCode, result.data)
        callback?.onReceiveValue(uris)
    }

    private fun printCurrentPage() {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "${getString(R.string.app_name)}_${System.currentTimeMillis()}"
        val printAdapter = webView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
    }

    private fun setAutoRefresh(intervalMs: Long) {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        autoRefreshIntervalMs = intervalMs
        autoRefreshButton.text = if (intervalMs > 0) "⏱•" else "⏱"
        if (intervalMs > 0) {
            autoRefreshHandler.postDelayed(autoRefreshRunnable, intervalMs)
        }
    }

    private fun showAutoRefreshMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, "כבוי")
        popup.menu.add(0, 1, 1, "כל 10 שניות")
        popup.menu.add(0, 2, 2, "כל 30 שניות")
        popup.menu.add(0, 3, 3, "כל דקה")
        popup.menu.add(0, 4, 4, "כל 5 דקות")
        popup.setOnMenuItemClickListener { item ->
            val ms = when (item.itemId) {
                1 -> 10_000L
                2 -> 30_000L
                3 -> 60_000L
                4 -> 300_000L
                else -> 0L
            }
            setAutoRefresh(ms)
            true
        }
        popup.show()
    }

    private fun hasFileAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestFileAccess() {
        runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                requestAllFilesAccess.launch(intent)
            } else {
                requestLegacyStoragePermissions.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }

    private fun hasContactsAccess(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestContactsAccess() {
        runOnUiThread { requestContactsPermission.launch(Manifest.permission.READ_CONTACTS) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        statusText = findViewById(R.id.statusText)
        pickButton = findViewById(R.id.pickButton)
        addressBar = findViewById(R.id.addressBar)
        addressText = findViewById(R.id.addressText)
        refreshButton = findViewById(R.id.refreshButton)
        autoRefreshButton = findViewById(R.id.autoRefreshButton)
        printButton = findViewById(R.id.printButton)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        refreshButton.setOnClickListener { webView.reload() }
        autoRefreshButton.setOnClickListener { showAutoRefreshMenu(it) }
        printButton.setOnClickListener { printCurrentPage() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.setGeolocationEnabled(true)
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.userAgentString = webView.settings.userAgentString
            .replace("; wv", "")
            .replace(" wv", "")

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(NAVIGATOR_SHARE_POLYFILL_JS, null)
            }
        }
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

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e: Exception) {
                    pendingFileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }

            override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = android.view.View.VISIBLE
                webView.visibility = android.view.View.GONE
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }

            override fun onHideCustomView() {
                fullscreenContainer.visibility = android.view.View.GONE
                fullscreenContainer.removeAllViews()
                webView.visibility = android.view.View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customView = null
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }

            // target="_blank" links / window.open(): this app has no tab UI, so the
            // requested URL is loaded into the same WebView instead of a new window.
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val transport = resultMsg.obj as WebView.WebViewTransport
                val redirectingWebView = WebView(this@MainActivity)
                redirectingWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: android.webkit.WebResourceRequest
                    ): Boolean {
                        webView.loadUrl(request.url.toString())
                        return true
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                        webView.loadUrl(url)
                        return true
                    }
                }
                transport.webView = redirectingWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                // Blobs only exist in the page's JS memory; fetch + base64-encode
                // them there and hand the bytes to Android for saving.
                val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val safeMime = mimeType ?: "application/octet-stream"
                val js = """
                    (function() {
                        fetch(${JSONObject.quote(url)})
                            .then(function(res) { return res.blob(); })
                            .then(function(blob) {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    var base64 = reader.result.split(',')[1];
                                    AndroidBlobDownload.saveBlob(base64, ${JSONObject.quote(guessedName)}, ${JSONObject.quote(safeMime)});
                                };
                                reader.readAsDataURL(blob);
                            })
                            .catch(function(e) {});
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
                return@setDownloadListener
            }

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

        webView.addJavascriptInterface(BlobDownloadBridge(this), "AndroidBlobDownload")

        webView.addJavascriptInterface(ShareBridge(this), "AndroidShare")

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

        webView.addJavascriptInterface(
            FileBridge(this, ::hasFileAccess, ::requestFileAccess),
            "AndroidFiles"
        )

        webView.addJavascriptInterface(
            ContactsBridge(this, ::hasContactsAccess, ::requestContactsAccess),
            "AndroidContacts"
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
        setAutoRefresh(0L) // new page: start with auto-refresh off

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
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        stopServer()
        super.onDestroy()
    }

    companion object {
        // Overrides navigator.share / navigator.canShare so file/text sharing
        // routes through Android's real share sheet via the AndroidShare bridge,
        // instead of relying on WebView's inconsistent native Web Share support.
        private const val NAVIGATOR_SHARE_POLYFILL_JS = """
            (function() {
                navigator.share = function(data) {
                    return new Promise(function(resolve, reject) {
                        try {
                            if (data && data.files && data.files.length > 0) {
                                var file = data.files[0];
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    var base64 = reader.result.split(',')[1];
                                    AndroidShare.shareFile(base64, file.name, file.type, data.title || '', data.text || '');
                                    resolve();
                                };
                                reader.onerror = function(e) { reject(e); };
                                reader.readAsDataURL(file);
                            } else {
                                AndroidShare.shareText(data && data.title || '', data && data.text || '', data && data.url || '');
                                resolve();
                            }
                        } catch (e) {
                            reject(e);
                        }
                    });
                };
                navigator.canShare = function(data) { return true; };
            })();
        """
    }
}
