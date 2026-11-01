package kr.or.kcaa.gwangju.portal.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kr.or.kcaa.gwangju.portal.BuildConfig
import org.json.JSONObject

/**
 * 웹 포털(window.AndroidPortalBridge)과 안드로이드 네이티브 간 양방향 통신 브릿지.
 * 웹 프론트엔드에서 `window.AndroidPortalBridge.getAppInfo()` 등의 형태로 호출할 수 있습니다.
 */
class PortalJsBridge(
    private val context: Context,
    private val onReloadRequested: () -> Unit,
    private val onClearCacheRequested: () -> Unit,
    private val onRequestNotificationPermission: () -> Unit = {},
    private val onSendTestNotification: () -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun getAppInfo(): String {
        return JSONObject().apply {
            put("platform", "android")
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("isNativeApp", true)
            put("notificationsEnabled", isNotificationEnabled())
        }.toString()
    }

    @JavascriptInterface
    fun isNativeApp(): Boolean = true

    @JavascriptInterface
    fun isNotificationEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @JavascriptInterface
    fun sendTestNotification() {
        mainHandler.post {
            onSendTestNotification()
        }
    }

    @JavascriptInterface
    fun getLogoBase64(): String {
        return try {
            context.assets.open("logo_base64.txt").bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun requestNotificationPermission() {
        mainHandler.post {
            onRequestNotificationPermission()
        }
    }

    @JavascriptInterface
    fun openNotificationSettings() {
        mainHandler.post {
            try {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "기기 알림 설정을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun reloadPortal() {
        mainHandler.post {
            onReloadRequested()
        }
    }

    @JavascriptInterface
    fun clearSessionAndCache() {
        mainHandler.post {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            onClearCacheRequested()
        }
    }

    companion object {
        const val BRIDGE_NAME = "AndroidPortalBridge"
    }
}
