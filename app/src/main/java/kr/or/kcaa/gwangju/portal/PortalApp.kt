package kr.or.kcaa.gwangju.portal

import android.app.Application
import android.webkit.CookieManager

/**
 * 앱 프로세스 시작 시 WebView 세션 쿠키(만료 시각이 없는 쿠키)를 폐기한다.
 *
 * 포털 서버가 접속 환경에 따라 쿠키 수명을 정한다.
 * - 사내 IP 또는 관리자 승인 완료: Max-Age 가 있는 영구 쿠키 → 그대로 유지
 * - 외부 IP + 미승인: 세션 쿠키 → 앱 종료 후 재실행 시 여기서 제거되어 로그아웃
 *
 * 스와이프 종료/프로세스 킬은 onDestroy 가 호출되지 않으므로 콜드 스타트 시점에 정리한다.
 */
class PortalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        clearSessionCookies()
    }

    companion object {
        fun clearSessionCookies() {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeSessionCookies(null)
            cookieManager.flush()
        }
    }
}
