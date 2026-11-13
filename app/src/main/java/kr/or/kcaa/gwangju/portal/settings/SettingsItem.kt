package kr.or.kcaa.gwangju.portal.settings

/** 설정 화면의 한 줄. 화면은 이 목록만 그리므로, 설정을 추가할 때는 카탈로그에 항목을 더하면 됩니다. */
sealed interface SettingsItem {

    /** 구역 제목 */
    data class Header(val title: String) : SettingsItem

    /** 안내 문구 */
    data class Note(val text: String) : SettingsItem

    enum class Kind {
        /** 읽기 전용 (값만 표시) */
        INFO,

        /** 하위 화면으로 이동 */
        NAV,

        /** 누르면 동작 실행 */
        ACTION,

        /** 누르면 주소 열기 (↗) */
        LINK,

        /** 켜고 끄는 스위치 */
        SWITCH
    }

    enum class Tone { NEUTRAL, GOOD, WARN }

    data class Row(
        val id: String,
        val title: String,
        val description: String? = null,
        val value: String? = null,
        val tone: Tone = Tone.NEUTRAL,
        val kind: Kind = Kind.INFO,
        val checked: Boolean = false,
        val enabled: Boolean = true,
        val danger: Boolean = false,
        val onClick: (() -> Unit)? = null,
        val onToggle: ((Boolean) -> Unit)? = null
    ) : SettingsItem
}

/** 설정 화면 한 장(제목과 항목 목록). 목록은 그릴 때마다 현재 상태로 다시 만듭니다. */
class SettingsPage(
    val id: String,
    val title: String,
    val build: (SettingsHost) -> List<SettingsItem>
)

/** 카탈로그가 화면에 요청하는 동작 */
interface SettingsHost {
    val context: android.content.Context
    val isAdmin: Boolean

    /** 포털 화면에서 읽은 바로가기 주소(키: training, orders, certificates, kakao, hr). 없으면 null */
    fun link(key: String): String?

    /** 알림 종류 스위치를 저장하는 동안 true (그동안 다른 스위치는 잠김) */
    val busy: Boolean

    fun navigate(pageId: String)
    fun refresh()
    fun setBusy(busy: Boolean)
    fun toast(message: String)

    /** 텍스트를 클립보드에 복사 */
    fun copyToClipboard(label: String, text: String)

    /** 메인 화면에 동작을 요청하고 설정 화면을 닫습니다. */
    fun requestMainAction(action: String, url: String? = null)

    fun requestNotificationPermission()
    fun sendTestNotification()
    fun openSystemNotificationSettings()
    fun confirm(message: String, onConfirmed: () -> Unit)
}
