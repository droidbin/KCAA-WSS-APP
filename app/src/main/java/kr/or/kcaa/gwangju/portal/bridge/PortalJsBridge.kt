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
import kr.or.kcaa.gwangju.portal.push.PortalPush
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
    private val onSendTestNotification: () -> Unit,
    private val onModalStateChanged: (Boolean) -> Unit,
    private val onPushTypeResult: (type: String, enabled: Boolean, saved: Boolean) -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onPortalAdminChanged: (Boolean) -> Unit,
    private val onPortalLinksChanged: (String) -> Unit
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
            // 네이티브 푸시(FCM): Firebase 설정이 포함된 빌드에서만 true. 웹뷰는 Web Push 미지원.
            put("pushSupported", PortalPush.isAvailable(context))
        }.toString()
    }

    @JavascriptInterface
    fun isNativeApp(): Boolean = true

    @JavascriptInterface
    fun isNotificationEnabled(): Boolean {
        if (!isTrustedContext()) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 푸시 지원 여부와 알림 종류별 수신 설정(JSON) */
    @JavascriptInterface
    fun getPushState(): String {
        if (!isTrustedContext()) return "{}"
        return PortalPush.stateJson(context)
    }

    @JavascriptInterface
    fun setPushTypeEnabled(type: String, enabled: Boolean) = runTrusted {
        // 저장 결과(성공 여부와 실제 저장된 값)를 웹에 돌려줘, 실패하면 스위치를 되돌리게 합니다.
        PortalPush.setTypeEnabled(context, type, enabled) { saved ->
            mainHandler.post { onPushTypeResult(type, PortalPush.isTypeEnabled(context, type), saved) }
        }
    }

    /** 포털 세션으로 확인한 권한(staff / admin / super_admin) — 권한 밖의 알림 종류는 서버에 보내지 않습니다. */
    @JavascriptInterface
    fun setPushRole(role: String) = runTrusted { PortalPush.setRole(context, role) }

    /** 포털 헤더의 [설정] 버튼: 앱 설정 화면을 엽니다. */
    @JavascriptInterface
    fun openAppSettings() = runTrusted { onOpenSettings() }

    /** 포털 화면의 관리자 메뉴 노출 여부(관리자 로그인 상태)를 알려, 설정 화면의 관리자 메뉴 표시에 씁니다. */
    @JavascriptInterface
    fun setPortalAdmin(admin: Boolean) = runTrusted { onPortalAdminChanged(admin) }

    /** 포털 화면에서 읽은 바로가기 주소(JSON 객체: 키 → 주소). 포털 주소가 바뀌어도 설정 화면이 따라가게 합니다. */
    @JavascriptInterface
    fun setPortalLinks(json: String) = runTrusted { onPortalLinksChanged(json.take(4000)) }

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

    /** 웹 다이얼로그(설정, 게시글 등) 열림/닫힘 상태 전달 — 열려 있는 동안 당겨서 새로고침을 끕니다. */
    @JavascriptInterface
    fun setModalOpen(open: Boolean) = runTrusted { onModalStateChanged(open) }

    @JavascriptInterface
    fun reloadPortal()= runTrusted { onReloadRequested() }

    @JavascriptInterface
    fun clearSessionAndCache() = runTrusted {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            // 기기 식별 쿠키는 로그인 정보가 아니므로 다시 심습니다.
            PortalPush.applyDeviceCookie(context)
            onClearCacheRequested()
        }
    }

    companion object {
        const val BRIDGE_NAME = "AndroidPortalBridge"
    }
}
