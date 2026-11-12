package kr.or.kcaa.gwangju.portal.settings

/**
 * 메인 화면(WebView)과 설정 화면 사이의 약속.
 * 설정 화면은 WebView 를 갖지 않으므로, 웹을 움직여야 하는 동작은 결과(액션 ID)로 돌려주고 메인 화면이 실행합니다.
 * 메인 화면은 아래 고정 액션만 실행합니다. (임의의 주소·스크립트는 받지 않음)
 */
object SettingsContract {
    // 메인 → 설정
    const val EXTRA_IS_ADMIN = "settings_is_admin"
    const val EXTRA_LINKS_JSON = "settings_links_json"

    // 설정 → 메인 (결과)
    const val EXTRA_ACTION = "settings_action"
    const val EXTRA_URL = "settings_url"

    const val ACTION_RELOAD = "reload"
    const val ACTION_CLEAR_SESSION = "clear_session"
    const val ACTION_OPEN_URL = "open_url"
    const val ACTION_ADMIN_LOGIN = "admin_login"      // 로그인·로그아웃 모두 같은 포털 버튼
    const val ACTION_ADMIN_EDU = "admin_edu"
    const val ACTION_ADMIN_SLACK = "admin_slack"
    const val ACTION_ADMIN_PUSH = "admin_push"
    const val ACTION_ADMIN_TRASH = "admin_trash"

    /** 관리자 기능 액션 → 포털 화면의 (숨겨진) 버튼 ID */
    val ADMIN_BUTTON_IDS = mapOf(
        ACTION_ADMIN_LOGIN to "admin-control",
        ACTION_ADMIN_EDU to "education-links-manage",
        ACTION_ADMIN_SLACK to "slack-notifications-manage",
        ACTION_ADMIN_PUSH to "push-notifications-manage",
        ACTION_ADMIN_TRASH to "board-trash"
    )
}
