package kr.or.kcaa.gwangju.portal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kr.or.kcaa.gwangju.portal.bridge.PortalJsBridge
import kr.or.kcaa.gwangju.portal.databinding.ActivityMainBinding
import kr.or.kcaa.gwangju.portal.util.PortalDownloadHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var lastBackPressedTime: Long = 0L
    private var isInitialPageLoaded: Boolean = false
    private var hasMainFrameError: Boolean = false

    // 파일/카메라 선택 결과 콜백
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        var results: Array<Uri>? = null

        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data != null && (data.dataString != null || data.clipData != null)) {
                val clipData = data.clipData
                if (clipData != null) {
                    results = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else {
                    data.data?.let { uri -> results = arrayOf(uri) }
                }
            } else if (cameraPhotoUri != null) {
                results = arrayOf(cameraPhotoUri!!)
            }
        }

        callback.onReceiveValue(results)
        filePathCallback = null
        cameraPhotoUri = null
    }

    // 알림 권한 요청 (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 권한 허용 여부와 무관하게 포털 동작 유지 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 첫 페이지가 로드되기 전까지 스플래시 화면 유지 (최대 1.5초 후에는 강제 해제)
        val startTime = System.currentTimeMillis()
        splashScreen.setKeepOnScreenCondition {
            !isInitialPageLoaded && (System.currentTimeMillis() - startTime < 1500L)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 상단 시스템 바(상태바) 및 하단 네비게이션 바와의 겹침 방지 (Android 15 Edge-to-Edge 대응)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.tvLoadingVersion.text = "v${BuildConfig.VERSION_NAME}"

        createNotificationChannel()
        setupWebView()
        setupSwipeRefresh()
        setupErrorView()
        setupBackNavigation()
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            val targetUrl = resolveInitialUrl(intent)
            loadPortalUrl(targetUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { deepLinkUrl ->
            if (deepLinkUrl.startsWith(BuildConfig.PORTAL_BASE_URL)) {
                loadPortalUrl(deepLinkUrl)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                textZoom = 100 // 시스템 폰트 크기에 의해 포털 레이아웃이 깨지는 현상 방지
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = true
                allowContentAccess = true

                // 포털 서버에서 안드로이드 전용 웹뷰임을 식별할 수 있도록 User-Agent 확장
                userAgentString = "$userAgentString KCAAWssAndroid/${BuildConfig.VERSION_NAME}"
            }

            // 웹-네이티브 브릿지 연결 (window.AndroidPortalBridge)
            addJavascriptInterface(
                PortalJsBridge(
                    context = this@MainActivity,
                    onReloadRequested = { reload() },
                    onClearCacheRequested = {
                        clearCache(true)
                        clearHistory()
                        loadPortalUrl(BuildConfig.PORTAL_BASE_URL)
                    },
                    onRequestNotificationPermission = {
                        requestNotificationPermissionIfNeeded()
                    },
                    onSendTestNotification = {
                        showTestNotification()
                    }
                ),
                PortalJsBridge.BRIDGE_NAME
            )

            // 스크롤이 최상단에 있을 때만 당겨서 새로고침(SwipeRefresh)이 동작하도록 설정
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                binding.swipeRefreshLayout.isEnabled = (scrollY == 0)
            }

            // 첨부파일 다운로드 처리
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                PortalDownloadHelper.enqueueDownload(
                    context = this@MainActivity,
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType
                )
            }

            webViewClient = PortalWebViewClient()
            webChromeClient = PortalWebChromeClient()
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeResources(
            R.color.portal_primary,
            R.color.portal_accent
        )
        binding.swipeRefreshLayout.setOnRefreshListener {
            if (isNetworkConnected()) {
                binding.webView.reload()
            } else {
                binding.swipeRefreshLayout.isRefreshing = false
                showErrorView()
            }
        }
    }

    private fun setupErrorView() {
        binding.btnRetry.setOnClickListener {
            if (isNetworkConnected()) {
                hideErrorView()
                binding.webView.reload()
            } else {
                Toast.makeText(this, R.string.error_network_title, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGoHome.setOnClickListener {
            if (isNetworkConnected()) {
                hideErrorView()
                loadPortalUrl(BuildConfig.PORTAL_BASE_URL)
            } else {
                Toast.makeText(this, R.string.error_network_title, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.errorContainer.visibility == View.VISIBLE && binding.webView.canGoBack() -> {
                        hideErrorView()
                        binding.webView.goBack()
                    }
                    binding.webView.canGoBack() -> {
                        binding.webView.goBack()
                    }
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressedTime < 2000L) {
                            finish()
                        } else {
                            lastBackPressedTime = now
                            Toast.makeText(
                                this@MainActivity,
                                R.string.msg_back_press_exit,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }

    private fun resolveInitialUrl(intent: Intent?): String {
        val dataUrl = intent?.dataString
        return if (!dataUrl.isNullOrBlank() && dataUrl.startsWith(BuildConfig.PORTAL_BASE_URL)) {
            dataUrl
        } else {
            BuildConfig.PORTAL_BASE_URL
        }
    }

    private fun loadPortalUrl(url: String) {
        if (!isNetworkConnected()) {
            isInitialPageLoaded = true
            showErrorView()
            return
        }
        hasMainFrameError = false
        hideErrorView()
        binding.webView.loadUrl(url)
    }

    private fun hideLoadingOverlay() {
        if (binding.loadingOverlay.visibility == View.VISIBLE) {
            binding.loadingOverlay.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.loadingOverlay.alpha = 1f
                }
        }
    }

    private fun showErrorView() {
        binding.loadingOverlay.visibility = View.GONE
        binding.errorContainer.visibility = View.VISIBLE
        binding.swipeRefreshLayout.isRefreshing = false
        binding.progressBar.visibility = View.GONE
    }

    private fun hideErrorView() {
        binding.errorContainer.visibility = View.GONE
    }

    private fun isNetworkConnected(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "요리광주 공지사항 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "요리광주 업무지원포털 공지사항 및 시스템 알림"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showTestNotification() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Toast.makeText(this, "기기 알림 권한이 비활성화되어 있습니다. 알림 켜기를 눌러주세요.", Toast.LENGTH_SHORT).show()
            requestNotificationPermissionIfNeeded()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("새로운 공지사항 및 포털 알림이 정상적으로 수신됩니다.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(1001, notification)
            Toast.makeText(this, "테스트 알림이 전송되었습니다. 상단바를 확인하세요.", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "알림 권한을 허용해 주세요.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun handleExternalScheme(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false

        if (scheme == "http" || scheme == "https") {
            return false
        }

        // tel:, mailto:, sms:, intent: 등 외부 앱 스킴 처리
        return try {
            if (scheme == "intent") {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    true
                } else {
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (!fallbackUrl.isNullOrBlank()) {
                        binding.webView.loadUrl(fallbackUrl)
                        true
                    } else {
                        intent.`package`?.let { pkg ->
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=$pkg")
                                )
                            )
                        }
                        true
                    }
                }
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun createCameraTempFileUri(): Uri? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val cameraDir = File(cacheDir, "camera").apply { if (!exists()) mkdirs() }
            val photoFile = File.createTempFile("PORTAL_${timeStamp}_", ".jpg", cameraDir)
            FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                photoFile
            )
        } catch (e: Exception) {
            null
        }
    }

    private val customCss: String by lazy {
        try {
            assets.open("portal_custom.css").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    private val customJs: String by lazy {
        try {
            assets.open("portal_custom.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    private fun injectPortalEnhancements(view: WebView) {
        if (customCss.isNotBlank()) {
            val encodedCss = Base64.encodeToString(
                customCss.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            val cssScript = """
                (function() {
                    var id = 'portal-custom-css';
                    if (!document.getElementById(id)) {
                        var style = document.createElement('style');
                        style.id = id;
                        style.type = 'text/css';
                        style.textContent = decodeURIComponent(escape(window.atob('$encodedCss')));
                        (document.head || document.documentElement).appendChild(style);
                    }
                })();
            """.trimIndent()
            view.evaluateJavascript(cssScript, null)
        }

        if (customJs.isNotBlank()) {
            val encodedJs = Base64.encodeToString(
                customJs.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            val jsScript = """
                (function() {
                    var id = 'portal-custom-js';
                    if (!document.getElementById(id)) {
                        var s = document.createElement('script');
                        s.id = id;
                        s.type = 'text/javascript';
                        s.textContent = decodeURIComponent(escape(window.atob('$encodedJs')));
                        (document.head || document.documentElement).appendChild(s);
                    }
                })();
            """.trimIndent()
            view.evaluateJavascript(jsScript, null)
        }
    }

    private inner class PortalWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val uri = request.url ?: return super.shouldInterceptRequest(view, request)
            val path = uri.path?.lowercase(Locale.ROOT) ?: ""
            // 웹 포털 및 서브 시스템(증명서 발급 등)의 로고 요청을 신규 투명 로고로 로컬 가로채기
            // CSP(img-src 'self')를 100% 충족하여 깨진 이미지 오류 방지
            if (path.endsWith("kcaa-symbol.png") || path.contains("kcaa-symbol")) {
                return try {
                    val stream = assets.open("logo_emblem_clean.png")
                    val headers = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Cache-Control" to "no-cache"
                    )
                    WebResourceResponse("image/png", "UTF-8", 200, "OK", headers, stream)
                } catch (e: Exception) {
                    super.shouldInterceptRequest(view, request)
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url ?: return false
            return handleExternalScheme(uri)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            hasMainFrameError = false
            binding.progressBar.progress = 0
            binding.progressBar.visibility = View.VISIBLE
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            super.onPageCommitVisible(view, url)
            if (!hasMainFrameError) {
                hideLoadingOverlay()
            }
            injectPortalEnhancements(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            isInitialPageLoaded = true
            binding.swipeRefreshLayout.isRefreshing = false
            binding.progressBar.visibility = View.GONE

            // 로그인 세션 및 인증 쿠키를 디스크에 즉시 동기화
            CookieManager.getInstance().flush()

            if (!hasMainFrameError) {
                hideErrorView()
                hideLoadingOverlay()
                injectPortalEnhancements(view)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                hasMainFrameError = true
                isInitialPageLoaded = true
                showErrorView()
            }
        }
    }

    private inner class PortalWebChromeClient : WebChromeClient() {

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                request.grant(request.resources)
            }
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.setProgressCompat(newProgress, true)
            if (newProgress >= 100) {
                binding.progressBar.visibility = View.GONE
            }
        }

        // <input type="file"> 클릭 시 카메라 촬영 + 파일 탐색기 동시 지원
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback

            val contentSelectionIntent = fileChooserParams.createIntent()

            val intentArray = mutableListOf<Intent>()
            val tempPhotoUri = createCameraTempFileUri()
            if (tempPhotoUri != null) {
                cameraPhotoUri = tempPhotoUri
                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, tempPhotoUri)
                }
                intentArray.add(takePictureIntent)
            }

            val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                putExtra(Intent.EXTRA_TITLE, getString(R.string.chooser_file_upload))
                if (intentArray.isNotEmpty()) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray.toTypedArray())
                }
            }

            return try {
                fileChooserLauncher.launch(chooserIntent)
                true
            } catch (e: ActivityNotFoundException) {
                this@MainActivity.filePathCallback = null
                cameraPhotoUri = null
                false
            }
        }

        // target="_blank" 또는 window.open() 팝업 링크 처리
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            val tempWebView = WebView(this@MainActivity)
            tempWebView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val targetUri = request.url
                    if (!handleExternalScheme(targetUri)) {
                        binding.webView.loadUrl(targetUri.toString())
                    }
                    tempWebView.destroy()
                    return true
                }
            }
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = tempWebView
            resultMsg.sendToTarget()
            return true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        CookieManager.getInstance().flush()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "kcaa_portal_notice_channel"
    }
}
