package kr.or.kcaa.gwangju.portal.util

import android.net.Uri
import java.util.Locale

/**
 * 앱 내부 WebView에서 열고, 네이티브 브릿지·권한·스타일 주입을 허용하는 포털 계열 도메인 목록.
 * 목록에 없는 http(s) 링크는 외부 브라우저(또는 해당 앱)로 엽니다.
 *
 * [동기화 규칙] 같은 내부 주소 목록이 웹 저장소(KCAA-WSS-Portal)의
 * shared/kcaa-shell/kcaa-session.js `INTERNAL_ORIGINS` 에도 있습니다.
 * 웹은 앱 모드(UA `KCAAWssAndroid/`)에서 이 주소들로 가는 새 창 링크를 같은 창 이동(location.assign)으로 바꿉니다.
 * 시스템 주소를 추가·변경할 때는 두 곳을 함께 고치세요.
 */
object PortalHosts {

    private val TRUSTED_HOSTS = setOf(
        "gwangju-work-portal.pages.dev",           // 업무지원포털 (메인)
        "gwangju-training-migration.pages.dev",    // 법정의무교육
        "gwangju-student-certificates.pages.dev",  // 수강생 증명서발급
        "gwangju-orders-migration.pages.dev",      // 발주요청
        "gwangju-hr-migration.pages.dev",          // 인사관리
        "gwangju-inventory.pages.dev"              // 재고관리
    )

    /** 푸시 알림이 열 수 있는 도메인: 포털·발주·증명서·재고관리 (알림 허브가 보내는 주소) */
    private val NOTIFICATION_HOSTS = setOf(
        "gwangju-work-portal.pages.dev",
        "gwangju-orders-migration.pages.dev",
        "gwangju-student-certificates.pages.dev",
        "gwangju-inventory.pages.dev"
    )

    fun isNotificationTarget(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return isTrusted(uri) && uri.host?.lowercase(Locale.ROOT) in NOTIFICATION_HOSTS
    }

    private const val SMB_ADMIN_HOST = "kcaa-smb-admin.blofinder.com"
    private const val SMB_ADMIN_PATH = "/admin-web/"

    /**
     * SMB Manager 관리자 웹 주소인지 확인합니다. WebView 신뢰 목록에는 넣지 않고 외부 브라우저로만 엽니다.
     * https, 정확한 호스트, 기본 포트, 사용자 정보 없음, 경로 /admin-web/ 로 시작, 쿼리 없음 (해시는 허용).
     */
    fun isSmbAdminTarget(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.host?.lowercase(Locale.ROOT) != SMB_ADMIN_HOST) return false
        if (uri.port != -1 && uri.port != 443) return false
        if (!uri.userInfo.isNullOrEmpty()) return false
        if (uri.encodedQuery != null) return false
        return uri.path?.startsWith(SMB_ADMIN_PATH) == true && !uri.path.orEmpty().contains("..")
    }

    /** 허용 도메인 목록 (기기 식별 쿠키 설정 등에 사용) */
    val hosts: Set<String> get() = TRUSTED_HOSTS

    fun isTrusted(uri: Uri?): Boolean {
        if (uri == null) return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host in TRUSTED_HOSTS
    }

    fun isTrusted(url: String?): Boolean =
        !url.isNullOrBlank() && isTrusted(runCatching { Uri.parse(url) }.getOrNull())
}
