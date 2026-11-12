package kr.or.kcaa.gwangju.portal.util

import android.net.Uri
import java.util.Locale

/**
 * 앱 내부 WebView에서 열고, 네이티브 브릿지·권한·스타일 주입을 허용하는 포털 계열 도메인 목록.
 * 목록에 없는 http(s) 링크는 외부 브라우저(또는 해당 앱)로 엽니다.
 */
object PortalHosts {

    private val TRUSTED_HOSTS = setOf(
        "gwangju-work-portal.pages.dev",           // 업무지원포털 (메인)
        "gwangju-training-migration.pages.dev",    // 법정의무교육
        "gwangju-student-certificates.pages.dev",  // 수강생 증명서발급
        "gwangju-orders-migration.pages.dev",      // 발주요청
        "gwangju-hr-migration.pages.dev"           // 인사관리
    )

    /** 푸시 알림이 열 수 있는 도메인: 포털·발주·증명서 (알림 허브가 보내는 주소) */
    private val NOTIFICATION_HOSTS = setOf(
        "gwangju-work-portal.pages.dev",
        "gwangju-orders-migration.pages.dev",
        "gwangju-student-certificates.pages.dev"
    )

    fun isNotificationTarget(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return isTrusted(uri) && uri.host?.lowercase(Locale.ROOT) in NOTIFICATION_HOSTS
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
