package com.shimonhoter.localhost

import android.Manifest
import android.app.AlertDialog
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
import android.view.View
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One open page: its own local server, root directory, and WebView instance. */
class Tab(
    val id: Long,
    var uri: Uri,
    var displayName: String,
    var rootDir: File,
    var tempDir: File?,
    var server: LocalHttpServer,
    var autoRefreshMs: Long = 0L
) {
    lateinit var webView: WebView
}

class MainActivity : AppCompatActivity() {

    private var nextTabId = 1L
    private val tabs = mutableListOf<Tab>()
    private var activeTabId: Long = -1L

    private fun activeTab(): Tab? = tabs.find { it.id == activeTabId }

    private lateinit var statusText: TextView
    private lateinit var pickButton: Button
    private lateinit var addressBar: View
    private lateinit var addressText: TextView
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var openFileButton: TextView
    private lateinit var menuButton: TextView
    private lateinit var tabStripScroll: HorizontalScrollView
    private lateinit var tabStrip: LinearLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var emptyState: View
    private lateinit var fullscreenContainer: FrameLayout

    // HTML5 fullscreen video state
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val requestNotificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* no-op: just needed so the OS allows notifications at all on API 33+ */ }

    private val autoRefreshHandler = Handler(Looper.getMainLooper())
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            val tab = activeTab() ?: return
            if (tab.autoRefreshMs > 0) {
                tab.webView.reload()
                autoRefreshHandler.postDelayed(this, tab.autoRefreshMs)
            }
        }
    }

    private val pickFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) openInNewTab(uri)
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

    private fun printCurrentPage() {
        val tab = activeTab() ?: return
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "${getString(R.string.app_name)}_${System.currentTimeMillis()}"
        val printAdapter = tab.webView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
    }

    private fun setAutoRefresh(intervalMs: Long) {
        val tab = activeTab() ?: return
        tab.autoRefreshMs = intervalMs
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        if (intervalMs > 0) {
            autoRefreshHandler.postDelayed(autoRefreshRunnable, intervalMs)
        }
    }

    private fun showAutoRefreshMenu(anchor: View) {
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

    private fun showOverflowMenu(anchor: View) {
        val tab = activeTab()
        val isFav = tab?.let { RecentFilesStore.isFavorite(this, it.uri.toString()) } ?: false
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, "⟳ רענון ידני")
        popup.menu.add(0, 1, 1, "⏱ רענון אוטומטי")
        popup.menu.add(0, 2, 2, "✕ סגור דף")
        popup.menu.add(0, 3, 3, if (isFav) "➖ הסר ממועדפים" else "➕ הוסף למועדפים")
        popup.menu.add(0, 4, 4, "🕘 היסטוריה")
        popup.menu.add(0, 5, 5, "⭐ מועדפים")
        popup.menu.add(0, 6, 6, "🖨 הדפסה")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> activeTab()?.webView?.reload()
                1 -> showAutoRefreshMenu(anchor)
                2 -> activeTabId.let { closeTab(it) }
                3 -> toggleFavoriteForActiveTab()
                4 -> showHistoryDialog()
                5 -> showFavoritesDialog()
                6 -> printCurrentPage()
            }
            true
        }
        popup.show()
    }

    private fun toggleFavoriteForActiveTab() {
        val tab = activeTab() ?: return
        val nowFav = RecentFilesStore.toggleFavorite(this, tab.uri.toString(), tab.displayName)
        Toast.makeText(this, if (nowFav) "נוסף למועדפים" else "הוסר מהמועדפים", Toast.LENGTH_SHORT).show()
    }

    private fun showHistoryDialog() {
        val entries = RecentFilesStore.getHistory(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "אין היסטוריה עדיין", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("היסטוריה")
            .setItems(labels) { _, which -> openInNewTab(Uri.parse(entries[which].uri)) }
            .show()
    }

    private fun showFavoritesDialog() {
        val entries = RecentFilesStore.getFavorites(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "אין מועדפים עדיין", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("מועדפים")
            .setItems(labels) { _, which -> openInNewTab(Uri.parse(entries[which].uri)) }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rebuildTabStrip() {
        tabStrip.removeAllViews()
        for (tab in tabs) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(4), dp(6))
                setBackgroundColor(if (tab.id == activeTabId) 0xFF2E4256.toInt() else 0xFF16212B.toInt())
            }
            val label = TextView(this).apply {
                text = tab.displayName
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener { switchToTab(tab.id) }
            }
            val close = TextView(this).apply {
                text = "✕"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { closeTab(tab.id) }
            }
            chip.addView(label)
            chip.addView(close)
            tabStrip.addView(chip)
        }
        tabStripScroll.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
    }

    private fun updateNavButtons() {
        val tab = activeTab()
        backButton.alpha = if (tab?.webView?.canGoBack() == true) 1f else 0.35f
        forwardButton.alpha = if (tab?.webView?.canGoForward() == true) 1f else 0.35f
    }

    private fun switchToTab(id: Long) {
        activeTabId = id
        val tab = activeTab() ?: return
        for (t in tabs) t.webView.visibility = if (t.id == id) View.VISIBLE else View.GONE

        addressBar.visibility = View.VISIBLE
        addressText.text = "http://127.0.0.1:${tab.server.port}/${tab.displayName}"
        emptyState.visibility = View.GONE
        webViewContainer.visibility = View.VISIBLE

        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        if (tab.autoRefreshMs > 0) {
            autoRefreshHandler.postDelayed(autoRefreshRunnable, tab.autoRefreshMs)
        }

        updateNavButtons()
        rebuildTabStrip()
    }

    private fun closeTab(id: Long) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx == -1) return
        val tab = tabs[idx]
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        tab.server.stop()
        tab.tempDir?.deleteRecursively()
        webViewContainer.removeView(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(idx)

        if (tabs.isEmpty()) {
            activeTabId = -1L
            addressBar.visibility = View.GONE
            webViewContainer.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            statusText.text = ""
            rebuildTabStrip()
        } else {
            val newActive = tabs[minOf(idx, tabs.size - 1)]
            switchToTab(newActive.id)
        }
    }

    /** Builds a fully configured WebView for [tab] (settings, JS bridges, chrome/web clients). */
    private fun createWebView(tab: Tab): WebView {
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.setGeolocationEnabled(true)
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.settings.setSupportMultipleWindows(true)
        wv.settings.userAgentString = wv.settings.userAgentString
            .replace("; wv", "")
            .replace(" wv", "")

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(NAVIGATOR_SHARE_POLYFILL_JS, null)
                view.evaluateJavascript(BLOB_DOWNLOAD_INTERCEPTOR_JS, null)
                if (tab.id == activeTabId) updateNavButtons()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
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

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                wv.visibility = View.GONE
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }

            override fun onHideCustomView() {
                fullscreenContainer.visibility = View.GONE
                fullscreenContainer.removeAllViews()
                if (tab.id == activeTabId) wv.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customView = null
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }

            // target="_blank" links / window.open(): this app has no tab UI for
            // spawning real new windows, so the requested URL loads in this same tab.
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
                        wv.loadUrl(request.url.toString())
                        return true
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                        wv.loadUrl(url)
                        return true
                    }
                }
                transport.webView = redirectingWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        wv.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
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
                wv.evaluateJavascript(js, null)
                return@setDownloadListener
            }

            val relativePath = try {
                java.net.URLDecoder.decode(Uri.parse(url).path ?: "", "UTF-8").trimStart('/')
            } catch (e: Exception) {
                null
            }
            val sourceFile = relativePath?.let { File(tab.rootDir, it) }
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

        wv.addJavascriptInterface(BlobDownloadBridge(this), "AndroidBlobDownload")
        wv.addJavascriptInterface(ShareBridge(this), "AndroidShare")
        wv.addJavascriptInterface(
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
        wv.addJavascriptInterface(FileBridge(this, ::hasFileAccess, ::requestFileAccess), "AndroidFiles")
        wv.addJavascriptInterface(
            ContactsBridge(this, ::hasContactsAccess, ::requestContactsAccess),
            "AndroidContacts"
        )

        return wv
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        pickButton = findViewById(R.id.pickButton)
        addressBar = findViewById(R.id.addressBar)
        addressText = findViewById(R.id.addressText)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        openFileButton = findViewById(R.id.openFileButton)
        menuButton = findViewById(R.id.menuButton)
        tabStripScroll = findViewById(R.id.tabStripScroll)
        tabStrip = findViewById(R.id.tabStrip)
        webViewContainer = findViewById(R.id.webViewContainer)
        emptyState = findViewById(R.id.emptyState)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        backButton.setOnClickListener { activeTab()?.webView?.let { if (it.canGoBack()) it.goBack() } }
        forwardButton.setOnClickListener { activeTab()?.webView?.let { if (it.canGoForward()) it.goForward() } }
        openFileButton.setOnClickListener { pickFile.launch(arrayOf("text/html")) }
        menuButton.setOnClickListener { showOverflowMenu(it) }
        pickButton.setOnClickListener { pickFile.launch(arrayOf("text/html")) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(this) {
            val tab = activeTab()
            when {
                tab?.webView?.canGoBack() == true -> tab.webView.goBack()
                tabs.size > 1 -> closeTab(activeTabId)
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
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
        if (uri != null) openInNewTab(uri)
    }

    /**
     * Opens [uri] as a new tab: serves it over 127.0.0.1 (from its own local
     * server) and loads it in a freshly created WebView. file:// uris are
     * served straight from their parent directory (so relative css/js/image
     * links keep working). content:// uris are copied once into a fresh
     * cache folder, since the local server can only serve real files on disk.
     */
    private fun openInNewTab(uri: Uri) {
        statusText.text = getString(R.string.status_starting)

        try {
            val rootDir: File
            val fileName: String
            var tempDir: File? = null

            if (uri.scheme == "file") {
                val file = File(requireNotNull(uri.path))
                rootDir = file.parentFile ?: file
                fileName = file.name
            } else {
                val dir = File(cacheDir, "page_${System.currentTimeMillis()}_$nextTabId").apply { mkdirs() }
                tempDir = dir
                val name = queryDisplayName(uri) ?: "index.html"
                val outFile = File(dir, name)
                contentResolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("לא ניתן לקרוא את הקובץ שנבחר")
                rootDir = dir
                fileName = name
            }

            val server = LocalHttpServer(rootDir)
            server.start()

            val tab = Tab(
                id = nextTabId++,
                uri = uri,
                displayName = fileName,
                rootDir = rootDir,
                tempDir = tempDir,
                server = server
            )
            tab.webView = createWebView(tab)
            tabs.add(tab)
            webViewContainer.addView(
                tab.webView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )

            if (uri.scheme == "content") {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // not all content:// grants are persistable (e.g. one-off SEND intents) — safe to ignore
                }
            }
            RecentFilesStore.addHistory(this, uri.toString(), fileName)

            tab.webView.loadUrl("http://127.0.0.1:${server.port}/$fileName")
            switchToTab(tab.id)
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

    override fun onDestroy() {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        for (t in tabs) {
            t.server.stop()
            t.tempDir?.deleteRecursively()
        }
        tabs.clear()
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

        // Catches downloads of blob: URLs at the moment of the click itself —
        // more reliable than reacting to Android's onDownloadStart callback,
        // which arrives asynchronously and can lose the race against pages
        // that call URL.revokeObjectURL() right after a.click().
        private const val BLOB_DOWNLOAD_INTERCEPTOR_JS = """
            (function() {
                if (window.__androidBlobInterceptorInstalled) return;
                window.__androidBlobInterceptorInstalled = true;
                window.__blobRegistry = window.__blobRegistry || {};

                var origCreateObjectURL = URL.createObjectURL.bind(URL);
                URL.createObjectURL = function(obj) {
                    var url = origCreateObjectURL(obj);
                    try { window.__blobRegistry[url] = obj; } catch (e) {}
                    return url;
                };

                var origRevokeObjectURL = URL.revokeObjectURL.bind(URL);
                URL.revokeObjectURL = function(url) {
                    try { delete window.__blobRegistry[url]; } catch (e) {}
                    return origRevokeObjectURL(url);
                };

                document.addEventListener('click', function(event) {
                    var el = event.target;
                    while (el && el.tagName !== 'A') el = el.parentElement;
                    if (!el) return;
                    var href = el.getAttribute('href') || '';
                    if (!el.hasAttribute('download') || href.indexOf('blob:') !== 0) return;
                    var blob = window.__blobRegistry[href];
                    if (!blob) return;

                    event.preventDefault();
                    event.stopImmediatePropagation();

                    var filename = el.getAttribute('download') || 'download';
                    var mimeType = blob.type || 'application/octet-stream';
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        var base64 = reader.result.split(',')[1];
                        AndroidBlobDownload.saveBlob(base64, filename, mimeType);
                    };
                    reader.readAsDataURL(blob);
                }, true);
            })();
        """
    }
}
