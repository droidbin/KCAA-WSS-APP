package kr.or.kcaa.gwangju.portal.settings

/** 설정 화면의 한 줄. 화면은 이 목록만 그리므로, 설정을 추가할 때는 카탈로그에 항목을 더하면 됩니다. */
sealed interface SettingsItem {

    /** 구역 제목 */
    data class Header(val title: String) : SettingsItem

    /** 안내 문구 */
    data class Note(val text: String) : SettingsItem

    /**
     * 화면 맨 위의 요약 카드(포털 패널 모양): 아이콘 상자 + 영문 소제목 + 제목 + 상태 알약 + 설명 + 주요 버튼.
     * 관리자 페이지 열기처럼 "이 화면에서 가장 먼저 할 일"을 눈에 띄게 둘 때 씁니다.
     */
    data class Hero(
        val eyebrow: String,
        val title: String,
        val description: String,
        val iconRes: Int,
        val chip: String? = null,
        val chipTone: Tone = Tone.NEUTRAL,
        val primaryLabel: String,
        val onPrimary: () -> Unit
    ) : SettingsItem

    /** 타일 카드 한 장 (아이콘 상자 + 제목 + 한 줄 설명) */
    data class Tile(
        val id: String,
        val title: String,
        val description: String? = null,
        val iconRes: Int,
        val onClick: () -> Unit
    )

    /** 타일 두 장을 한 줄에 놓습니다. [Tiles.of] 로 목록을 짝지어 만듭니다. (홀수면 오른쪽은 비움) */
    data class Tiles(val left: Tile, val right: Tile?) : SettingsItem {
        companion object {
            fun of(tiles: List<Tile>): List<Tiles> = tiles.chunked(2).map { Tiles(it[0], it.getOrNull(1)) }
        }
    }

    /** 화면 폭 전체 버튼 (로그아웃처럼 따로 떨어진 동작). 위험 동작은 [danger] 로 연한 빨강 바탕 */
    data class ActionButton(
        val id: String,
        val label: String,
        val iconRes: Int,
        val danger: Boolean = false,
        val onClick: () -> Unit
    ) : SettingsItem

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
