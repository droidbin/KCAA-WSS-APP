package kr.or.kcaa.gwangju.portal.bridge

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kr.or.kcaa.gwangju.portal.BuildConfig
import org.json.JSONObject

/**
 * 웹 포털(window.AndroidPortalBridge)과 안드로이드 네이티브 간 통신 브릿지.
 *
 * 보안: 모든 메서드는 현재 WebView 메인 프레임이 포털 계열 허용 도메인일 때만 동작합니다.
 * (isTrustedContext 가 false 이면 조회 메서드는 빈 값, 동작 메서드는 무시)
 */
class PortalJsBridge(
    private val context: Context,
    private val isTrustedContext: () -> Boolean,
    private val onReloadRequested: () -> Unit,
    private val onClearCacheRequested: () -> Unit,
    private val onRequestNotificationPermission: () -> Unit,
    private val onOpenNotificationSettings: () -> Unit,
    private val onSendTestNotification: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private inline fun runTrusted(crossinline action: () -> Unit) {
        if (!isTrustedContext()) return
        mainHandler.post { action() }
    }

    @JavascriptInterface
    fun getAppInfo(): String {
        if (!isTrustedContext()) return "{}"
        return JSONObject().apply {
            put("platform", "android")
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("isNativeApp", true)
            put("notificationsEnabled", isNotificationEnabled())
            // 앱은 아직 네이티브 푸시(FCM) 수신을 지원하지 않습니다. 웹뷰는 Web Push 미지원.
            put("pushSupported", false)
        }.toString()
    }

    @JavascriptInterface
    fun isNativeApp(): Boolean = true

    @JavascriptInterface
    fun isNotificationEnabled(): Boolean {
        if (!isTrustedContext()) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @JavascriptInterface
    fun sendTestNotification() = runTrusted { onSendTestNotification() }

    @JavascriptInterface
    fun requestNotificationPermission() = runTrusted { onRequestNotificationPermission() }

    @JavascriptInterface
    fun openNotificationSettings() = runTrusted { onOpenNotificationSettings() }

    @JavascriptInterface
    fun showToast(message: String) = runTrusted {
        Toast.makeText(context, message.take(120), Toast.LENGTH_SHORT).show()
    }

    @JavascriptInterface
    fun reloadPortal() = runTrusted { onReloadRequested() }

    @JavascriptInterface
    fun clearSessionAndCache() = runTrusted {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        onClearCacheRequested()
    }

    companion object {
        const val BRIDGE_NAME = "AndroidPortalBridge"
    }
}
