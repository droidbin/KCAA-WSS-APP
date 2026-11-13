package kr.or.kcaa.gwangju.portal

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import kr.or.kcaa.gwangju.portal.bridge.PortalJsBridge
import kr.or.kcaa.gwangju.portal.databinding.ActivityMainBinding
import kr.or.kcaa.gwangju.portal.push.PortalNotifications
import kr.or.kcaa.gwangju.portal.push.PortalPush
import kr.or.kcaa.gwangju.portal.settings.SettingsActivity
import kr.or.kcaa.gwangju.portal.settings.SettingsContract
import kr.or.kcaa.gwangju.portal.util.PortalDownloadHelper
import kr.or.kcaa.gwangju.portal.util.PortalHosts
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var lastBackPressedTime: Long = 0L
    private var hasMainFrameError: Boolean = false
    private var loadingStartTime: Long = 0L
    private var isOverlayDismissScheduled: Boolean = false
    private var isOverlayDismissed: Boolean = false

    /** 현재 메인 프레임이 포털 허용 도메인인지 여부 (JS 브릿지 스레드에서 읽음) */
    @Volatile
    private var isCurrentPageTrusted: Boolean = false

    /** 웹 다이얼로그(게시글 등)가 열려 있는지 여부 — 열려 있으면 당겨서 새로고침 비활성화 */
    private var isWebModalOpen: Boolean = false

    /** 포털이 알려 준 관리자 로그인 여부 (설정 화면의 관리자 메뉴 표시용) */
    @Volatile
    private var portalAdmin: Boolean = false

    /** 포털 화면에서 읽은 바로가기 주소(JSON). 모듈 화면에서 설정을 열 때도 쓰도록 저장해 둡니다. */
    @Volatile
    private var portalLinksJson: String = ""

    /** 지금 보고 있는 주소 (설정 버튼 표시 여부 판단용) */
    private var currentUrl: String? = null

    /** 포털 메인으로 이동한 뒤 눌러야 할 포털 버튼 ID (관리자 기능) */
    private var pendingPortalClick: String? = null

    private var isKeyboardVisible: Boolean = false

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

    // 알림 권한 요청 (Android 13+) — 설정 화면에서 사용자가 요청할 때만 실행
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        dispatchNotificationState()
        PortalPush.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        loadingStartTime = SystemClock.elapsedRealtime()
        splashScreen.setKeepOnScreenCondition { false }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSystemBars()
        binding.tvLoadingVersion.text = getString(R.string.app_version_format, BuildConfig.VERSION_NAME)

        PortalPush.ensureChannels(this)
        portalLinksJson = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PORTAL_LINKS, "").orEmpty()
        setupWebView()
        PortalPush.applyDeviceCookie(this)
        PortalPush.sync(this)
        setupSwipeRefresh()
        setupErrorView()
        setupBackNavigation()
        setupSettingsFab()
        updateSystemBarAppearance()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            loadPortalUrl(resolveInitialUrl(intent))
            // 첫 실행 때 알림 권한을 한 번만 먼저 묻습니다 (Android 13+). 거부한 뒤에는 설정 화면의 [알림 허용]으로만 요청.
            PortalNotifications.askOnFirstRun(this, notificationPermissionLauncher)
            // 런처 아이콘 길게 누르기 → 설정으로 앱이 새로 시작된 경우
            if (intent?.action == ACTION_OPEN_SETTINGS) binding.root.post { openSettings() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_SETTINGS) {
            openSettings()
            return
        }
        targetUrlFrom(intent)?.let { loadPortalUrl(it) }
    }

    /**
     * Edge-to-Edge(Android 15+ 강제) 환경에서 상태바/내비게이션 바 영역을 별도 스크림 뷰로 칠합니다.
     * - 상태바: 포털 헤더와 같은 흰색(웹 화면) / 로딩·오류 화면에서는 페이지 배경색
     * - 내비게이션 바: 포털 페이지 배경(#F6F7F9)과 같은 색, 아이콘은 항상 어둡게
     * 키보드(IME)가 올라오면 하단 여백을 키보드 높이만큼 확보합니다.
     */
    private fun setupSystemBars() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardNow = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (keyboardNow != isKeyboardVisible) {
                isKeyboardVisible = keyboardNow
                updateSettingsFab()
            }
            v.setPadding(bars.left, 0, bars.right, 0)
            binding.statusBarScrim.updateLayoutParams { height = bars.top }
            binding.navBarScrim.updateLayoutParams { height = maxOf(bars.bottom, ime.bottom) }
            insets
        }
    }

    /** 로딩/오류 오버레이 노출 여부에 따라 상태바 배경색을 맞춤 (포털이 밝은 테마이므로 아이콘은 항상 어둡게) */
    private fun updateSystemBarAppearance() {
        val overlayShown = binding.loadingOverlay.visibility == View.VISIBLE ||
            binding.errorContainer.visibility == View.VISIBLE
        binding.statusBarScrim.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (overlayShown) R.color.portal_background else R.color.portal_surface
            )
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        updateSettingsFab()
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
                allowFileAccess = false
                allowContentAccess = true

                // 포털 서버에서 안드로이드 전용 웹뷰임을 식별할 수 있도록 User-Agent 확장
                userAgentString = "$userAgentString KCAAWssAndroid/${BuildConfig.VERSION_NAME}"
            }

            // 웹-네이티브 브릿지 연결 (window.AndroidPortalBridge) — 포털 허용 도메인에서만 동작
            addJavascriptInterface(
                PortalJsBridge(
                    context = this@MainActivity,
                    isTrustedContext = { isCurrentPageTrusted },
                    // 새로고침은 현재 페이지가 아니라 포털 메인으로 이동
                    onReloadRequested = { loadPortalUrl(BuildConfig.PORTAL_BASE_URL) },
                    onClearCacheRequested = {
                        clearCache(true)
                        clearHistory()
                        loadPortalUrl(BuildConfig.PORTAL_BASE_URL)
                    },
                    onRequestNotificationPermission = { requestNotificationPermission() },
                    onOpenNotificationSettings = { openNotificationSettings() },
                    onSendTestNotification = { showTestNotification() },
                    onModalStateChanged = { open ->
                        isWebModalOpen = open
                        updateSwipeRefreshEnabled()
                    },
                    onPushTypeResult = { type, enabled, saved -> dispatchPushTypeResult(type, enabled, saved) },
                    onOpenSettings = { openSettings() },
                    onPortalAdminChanged = { admin -> portalAdmin = admin },
                    onPortalLinksChanged = { json ->
                        portalLinksJson = json
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(KEY_PORTAL_LINKS, json).apply()
                    }
                ),
                PortalJsBridge.BRIDGE_NAME
            )

            // 스크롤이 최상단에 있을 때만 당겨서 새로고침(SwipeRefresh)이 동작하도록 설정
            setOnScrollChangeListener { _, _, _, _, _ -> updateSwipeRefreshEnabled() }

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

    /** 페이지가 최상단이고 웹 다이얼로그가 닫혀 있을 때만 당겨서 새로고침 허용 */
    private fun updateSwipeRefreshEnabled() {
        binding.swipeRefreshLayout.isEnabled = !isWebModalOpen && binding.webView.scrollY == 0
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeResources(
            R.color.portal_accent,
            R.color.portal_accent
        )
        // 새로고침은 현재 페이지를 다시 불러오지 않고 포털 메인으로 이동합니다. (오프라인이면 오류 화면)
        binding.swipeRefreshLayout.setOnRefreshListener {
            loadPortalUrl(BuildConfig.PORTAL_BASE_URL)
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

    /**
     * 딥링크 또는 푸시 알림이 가리키는 주소. 포털 계열 허용 도메인(https)일 때만 사용합니다.
     * (알림은 intent data, 시스템이 직접 표시한 FCM 알림은 "url" extra 로 전달)
     */
    private fun targetUrlFrom(intent: Intent?): String? {
        val candidate = intent?.dataString ?: intent?.getStringExtra("url")
        return candidate?.takeIf { PortalHosts.isTrusted(it) }
    }

    private fun resolveInitialUrl(intent: Intent?): String =
        targetUrlFrom(intent) ?: BuildConfig.PORTAL_BASE_URL

    private fun loadPortalUrl(url: String) {
        if (!isNetworkConnected()) {
            showErrorView()
            return
        }
        hasMainFrameError = false
        hideErrorView()
        binding.webView.loadUrl(url)
    }

    private fun hideLoadingOverlay() {
        if (isOverlayDismissed || isOverlayDismissScheduled) return

        val elapsed = SystemClock.elapsedRealtime() - loadingStartTime
        val remaining = MIN_BRAND_DISPLAY_DURATION - elapsed

        if (remaining > 0) {
            isOverlayDismissScheduled = true
            binding.loadingOverlay.postDelayed({ performFadeOutOverlay() }, remaining)
        } else {
            performFadeOutOverlay()
        }
    }

    private fun performFadeOutOverlay() {
        if (isOverlayDismissed) return
        if (binding.loadingOverlay.visibility != View.VISIBLE) return

        isOverlayDismissed = true
        binding.loadingOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.loadingOverlay.visibility = View.GONE
                binding.loadingOverlay.alpha = 1f
                updateSystemBarAppearance()
            }
            .start()
    }

    private fun showErrorView() {
        isOverlayDismissed = true
        binding.loadingOverlay.animate().cancel()
        binding.loadingOverlay.visibility = View.GONE
        binding.errorContainer.visibility = View.VISIBLE
        binding.swipeRefreshLayout.isRefreshing = false
        binding.progressBar.visibility = View.GONE
        updateSystemBarAppearance()
    }

    private fun hideErrorView() {
        binding.errorContainer.visibility = View.GONE
        updateSystemBarAppearance()
    }

    private fun isNetworkConnected(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    // ───────────────────────── 알림 ─────────────────────────

    private fun areNotificationsEnabled(): Boolean = PortalNotifications.areEnabled(this)

    /**
     * 알림 권한 요청: 처음이면 시스템 대화상자, 영구 거부 상태면 시스템 알림 설정으로 이동합니다.
     * (공용 동작은 [PortalNotifications] — 설정 화면과 같은 코드를 씁니다.)
     */
    private fun requestNotificationPermission() {
        if (PortalNotifications.requestPermission(this, notificationPermissionLauncher)) {
            dispatchNotificationState()
        }
    }

    private fun openNotificationSettings() = PortalNotifications.openSystemSettings(this)

    private fun showTestNotification() = PortalNotifications.sendTest(this, notificationPermissionLauncher)

    /** 웹에 현재 알림 권한 상태를 이벤트로 전달 */
    private fun dispatchNotificationState() {
        if (!isCurrentPageTrusted) return
        val enabled = areNotificationsEnabled()
        binding.webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('portal:notification-state',{detail:{enabled:$enabled}}));",
            null
        )
    }

    /** 웹에 알림 종류 스위치의 저장 결과를 전달 (이전 버전 호환용) */
    private fun dispatchPushTypeResult(type: String, enabled: Boolean, saved: Boolean) {
        if (!isCurrentPageTrusted || type !in PortalPush.TYPES || isDestroyed) return
        binding.webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('portal:push-type-result'," +
                "{detail:{type:'$type',enabled:$enabled,ok:$saved}}));",
            null
        )
    }

    // ───────────────────────── 설정 화면 ─────────────────────────

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) handleSettingsResult(result.data)
    }

    /** 앱 설정 화면을 엽니다. 포털 [설정] 버튼, 모듈 화면의 설정 버튼, 런처 바로가기가 모두 여기로 모입니다. */
    private fun openSettings() {
        if (isDestroyed || isFinishing) return
        settingsLauncher.launch(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsContract.EXTRA_IS_ADMIN, portalAdmin)
                .putExtra(SettingsContract.EXTRA_LINKS_JSON, portalLinksJson)
        )
    }

    /** 설정 화면이 요청한 동작을 실행합니다. 정해진 액션만 처리하고, 주소는 https 만 받습니다. */
    private fun handleSettingsResult(data: Intent?) {
        val action = data?.getStringExtra(SettingsContract.EXTRA_ACTION) ?: return
        when (action) {
            SettingsContract.ACTION_RELOAD -> loadPortalUrl(BuildConfig.PORTAL_BASE_URL)
            SettingsContract.ACTION_CLEAR_SESSION -> clearSessionAndCache()
            SettingsContract.ACTION_OPEN_URL -> {
                val uri = runCatching { Uri.parse(data.getStringExtra(SettingsContract.EXTRA_URL)) }.getOrNull()
                if (uri != null && uri.scheme.equals("https", ignoreCase = true)) {
                    if (PortalHosts.isTrusted(uri)) loadPortalUrl(uri.toString()) else openExternally(uri)
                }
            }
            else -> SettingsContract.ADMIN_BUTTON_IDS[action]?.let { clickPortalButton(it) }
        }
    }

    /** 캐시와 로그인 세션을 지우고 포털을 다시 시작합니다. 기기 식별 쿠키는 로그인 정보가 아니므로 다시 심습니다. */
    private fun clearSessionAndCache() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            PortalPush.applyDeviceCookie(this)
            binding.webView.clearCache(true)
            binding.webView.clearHistory()
            loadPortalUrl(BuildConfig.PORTAL_BASE_URL)
        }
    }

    private val portalHost: String? by lazy { Uri.parse(BuildConfig.PORTAL_BASE_URL).host?.lowercase(Locale.ROOT) }

    /** 포털 메인(헤더에 [설정] 버튼이 있는 화면)인지 */
    private fun isPortalMain(url: String?): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty()
        return uri.host?.lowercase(Locale.ROOT) == portalHost && (path.isEmpty() || path == "/")
    }

    /** 관리자 기능은 포털 메인의 (숨겨진) 버튼으로 동작하므로, 다른 화면이면 포털 메인으로 이동한 뒤 누릅니다. */
    private fun clickPortalButton(elementId: String) {
        if (isPortalMain(currentUrl) && !hasMainFrameError) {
            runPortalClick(elementId)
        } else {
            pendingPortalClick = elementId
            loadPortalUrl(BuildConfig.PORTAL_BASE_URL)
        }
    }

    private fun runPortalClick(elementId: String) {
        binding.webView.evaluateJavascript(
            "(function(){var e=document.getElementById('$elementId');if(e)e.click();})();",
            null
        )
    }

    // ───────────────────────── 설정 버튼 (모듈 화면) ─────────────────────────

    /**
     * 포털 메인에는 헤더에 [설정] 버튼이 있지만 발주·증명서·법정의무교육·인사관리 같은 모듈 화면에는 없으므로,
     * 앱이 직접 그리는 작은 설정 버튼을 띄웁니다. 웹 화면의 마크업과 무관하게 동작하며, 위아래로 끌어 옮길 수 있습니다.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSettingsFab() {
        val fab = binding.fabSettings
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        fab.translationY = prefs.getFloat(KEY_FAB_OFFSET, 0f).coerceAtMost(0f)

        var downY = 0f
        var startTranslation = 0f
        var dragging = false
        fab.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startTranslation = view.translationY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - downY
                    if (!dragging && kotlin.math.abs(delta) > touchSlop) dragging = true
                    if (dragging) {
                        val parentHeight = (view.parent as View).height.toFloat()
                        val lowest = -(parentHeight - view.height * 2f).coerceAtLeast(0f)
                        view.translationY = (startTranslation + delta).coerceIn(lowest, 0f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putFloat(KEY_FAB_OFFSET, view.translationY).apply()
                    } else {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        fab.setOnClickListener { openSettings() }
    }

    /** 모듈 화면(포털 메인이 아닌 신뢰 도메인 화면)이나 오류 화면에서만 설정 버튼을 보여 줍니다. */
    private fun updateSettingsFab() {
        val errorShown = binding.errorContainer.visibility == View.VISIBLE
        val loadingShown = binding.loadingOverlay.visibility == View.VISIBLE
        val onModulePage = PortalHosts.isTrusted(currentUrl) && !isPortalMain(currentUrl)
        val show = !loadingShown && !isKeyboardVisible && (errorShown || onModulePage)
        binding.fabSettings.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ───────────────────────── 링크 처리 ─────────────────────────

    /**
     * 링크 이동 정책
     * - 포털 허용 도메인(https): 앱 내부 WebView에서 열기 → false
     * - 그 외 http(s): 외부 브라우저(또는 카카오톡 등 해당 앱)로 열기 → true
     * - tel:, mailto:, intent: 등: 외부 앱 실행 → true
     */
    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme == "http" || scheme == "https") {
            if (PortalHosts.isTrusted(uri)) return false
            openExternally(uri)
            return true
        }
        return handleExternalScheme(uri, scheme)
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.msg_open_external_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleExternalScheme(uri: Uri, scheme: String): Boolean {
        return try {
            if (scheme == "intent") {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    // 외부 페이지가 앱 내부 컴포넌트를 직접 호출하지 못하도록 차단
                    component = null
                    selector = null
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (!fallbackUrl.isNullOrBlank()) {
                        val fallbackUri = Uri.parse(fallbackUrl)
                        if (PortalHosts.isTrusted(fallbackUri)) {
                            binding.webView.loadUrl(fallbackUrl)
                        } else {
                            openExternally(fallbackUri)
                        }
                    } else {
                        intent.`package`?.let { pkg ->
                            startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                            )
                        }
                    }
                }
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
            true
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

    // ───────────────────────── 포털 스타일/스크립트 주입 ─────────────────────────

    private val customCss: String by lazy { readAsset("portal_custom.css") }
    private val customJs: String by lazy { readAsset("portal_custom.js") }

    private fun readAsset(name: String): String = try {
        assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        ""
    }

    private fun buildInjectionScript(id: String, tag: String, content: String): String {
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return """
            (function() {
                var id = '$id';
                if (document.getElementById(id)) return;
                var el = document.createElement('$tag');
                el.id = id;
                el.textContent = decodeURIComponent(escape(window.atob('$encoded')));
                (document.head || document.documentElement).appendChild(el);
            })();
        """.trimIndent()
    }

    private fun injectPortalEnhancements(view: WebView, url: String?) {
        // 포털 계열 도메인에만 앱 전용 스타일/스크립트 주입
        if (!PortalHosts.isTrusted(url)) return
        if (customCss.isNotBlank()) {
            view.evaluateJavascript(buildInjectionScript("portal-custom-css", "style", customCss), null)
        }
        if (customJs.isNotBlank()) {
            view.evaluateJavascript(buildInjectionScript("portal-custom-js", "script", customJs), null)
        }
    }

    private inner class PortalWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val uri = request.url ?: return super.shouldInterceptRequest(view, request)
            // 포털 및 서브 시스템의 로고 요청을 앱 내장 투명 로고로 대체 (CSP img-src 'self' 충족)
            if (PortalHosts.isTrusted(uri) &&
                (uri.path?.lowercase(Locale.ROOT) ?: "").contains("kcaa-symbol")
            ) {
                return try {
                    val stream = assets.open("logo_emblem_clean.png")
                    val headers = mapOf("Cache-Control" to "no-cache")
                    WebResourceResponse("image/png", null, 200, "OK", headers, stream)
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
            // iframe 등 하위 프레임의 http(s) 이동은 그대로 둠
            if (!request.isForMainFrame) {
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                if (scheme == "http" || scheme == "https") return false
            }
            return handleNavigation(uri)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            isCurrentPageTrusted = PortalHosts.isTrusted(url)
            currentUrl = url
            updateSettingsFab()
            // 새 페이지에서는 다이얼로그가 닫힌 상태로 시작 (JS 가 다시 알려줌)
            isWebModalOpen = false
            updateSwipeRefreshEnabled()
            hasMainFrameError = false
            binding.progressBar.progress = 0
            binding.progressBar.visibility = View.VISIBLE
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            isCurrentPageTrusted = PortalHosts.isTrusted(url)
            currentUrl = url
            updateSettingsFab()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            super.onPageCommitVisible(view, url)
            if (!hasMainFrameError) hideLoadingOverlay()
            injectPortalEnhancements(view, url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            binding.swipeRefreshLayout.isRefreshing = false
            binding.progressBar.visibility = View.GONE

            // 로그인 세션 및 인증 쿠키를 디스크에 즉시 동기화
            CookieManager.getInstance().flush()

            if (!hasMainFrameError) {
                hideErrorView()
                hideLoadingOverlay()
                injectPortalEnhancements(view, url)
                // 설정 화면에서 요청한 관리자 기능: 포털 메인이 뜬 뒤 해당 버튼을 누름
                pendingPortalClick?.let { elementId ->
                    if (isPortalMain(url)) {
                        pendingPortalClick = null
                        view.postDelayed({ runPortalClick(elementId) }, 800)
                    }
                }
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
                showErrorView()
            }
        }
    }

    private inner class PortalWebChromeClient : WebChromeClient() {

        // 카메라/마이크 등 웹 권한은 포털 허용 도메인에만 부여
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                if (PortalHosts.isTrusted(request.origin)) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
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
                    if (!handleNavigation(targetUri)) {
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
        // 시스템 설정에서 알림을 바꾸고 돌아온 경우 설정 모달 상태 갱신
        dispatchNotificationState()
        PortalPush.sync(this)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.loadingOverlay.animate().cancel()
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            destroy()
        }
        // 사용자가 앱을 종료한 경우(뒤로가기 등) 승인되지 않은 세션 쿠키 폐기
        if (isFinishing) PortalApp.clearSessionCookies()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = PortalPush.CHANNEL_NOTICE
        private const val TEST_NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "portal_app_prefs"
        private const val KEY_PORTAL_LINKS = "portal_links_json"
        private const val KEY_FAB_OFFSET = "settings_fab_offset"

        /** 런처 아이콘 길게 누르기 바로가기 "설정" (res/xml/shortcuts.xml) */
        const val ACTION_OPEN_SETTINGS = "kr.or.kcaa.gwangju.portal.action.OPEN_SETTINGS"
        private const val MIN_BRAND_DISPLAY_DURATION = 2000L // 브랜드 로고 및 정식 명칭 최소 노출 시간 (2.0초)
    }
}
