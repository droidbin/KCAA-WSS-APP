package kr.or.kcaa.gwangju.portal.settings

import kr.or.kcaa.gwangju.portal.BuildConfig
import kr.or.kcaa.gwangju.portal.R
import kr.or.kcaa.gwangju.portal.push.PortalNotifications
import kr.or.kcaa.gwangju.portal.push.PortalPush
import kr.or.kcaa.gwangju.portal.settings.SettingsItem.Header
import kr.or.kcaa.gwangju.portal.settings.SettingsItem.Kind
import kr.or.kcaa.gwangju.portal.settings.SettingsItem.Note
import kr.or.kcaa.gwangju.portal.settings.SettingsItem.Row
import kr.or.kcaa.gwangju.portal.settings.SettingsItem.Tone

/**
 * 설정 화면에 들어가는 모든 항목의 등록처.
 *
 * 설정을 추가하려면:
 *  1. 알림 종류가 아니라면 해당 [SettingsPage] 의 `build` 목록에 [Row] 를 한 줄 더하고,
 *  2. 새 하위 화면이 필요하면 [SettingsPage] 를 만들어 [pages] 에 넣은 뒤 [Kind.NAV] 행으로 연결합니다.
 * 항목을 조건부로 보이게 하려면 목록을 만드는 코드에서 `if` 로 넣거나 빼면 됩니다. (화면 코드는 건드리지 않음)
 */
object SettingsCatalog {

    const val ROOT = "root"
    private const val NOTIFICATIONS = "notifications"
    private const val SHORTCUTS = "shortcuts"
    private const val ADMIN = "admin"
    private const val APP = "app"
    private const val INFO = "info"

    private const val PRIVACY_URL = "https://gwangju-work-portal.pages.dev/privacy"
    private const val ORDER_NOTIFY_URL = "https://gwangju-orders-migration.pages.dev/orders/notify"

    /** 알림 종류 한 개 (권한 규칙은 [PortalPush.permittedTypes] 가 정함) */
    private class PushKind(val type: String, val title: String, val description: String)

    private val workKinds = listOf(
        PushKind("notice", "새 공지", "포털에 새 공지가 등록되면 알림"),
        PushKind("order", "발주 처리상태", "내 발주의 처리상태가 바뀌면 알림"),
        PushKind("support", "업무요청 답변", "내 업무요청에 답변이 등록되면 알림"),
        PushKind("certificate", "증명서 수정요청 결과", "내 수정요청이 승인·반려되면 알림")
    )
    private val adminKinds = listOf(
        PushKind("orderAdmin", "새 발주 접수", "새 발주요청이 접수되면 알림"),
        PushKind("certificateAdmin", "새 증명서 수정요청", "증명서 수정요청이 접수되면 알림"),
        PushKind("inventoryAdmin", "재고 입·출고", "재고관리에 입고·출고가 새로 등록되면 알림")
    )

    /** 포털 화면에서 링크를 읽지 못했을 때 쓰는 기본 주소 */
    private class Shortcut(val key: String, val title: String, val description: String, val fallback: String)

    private val shortcuts = listOf(
        Shortcut("training", "법정의무교육", "의무교육 수강하기", "https://gwangju-training-migration.pages.dev/"),
        Shortcut("orders", "발주요청서 작성", "물품 및 수업자재 신청", "https://gwangju-orders-migration.pages.dev/orders/request"),
        Shortcut("certificates", "수강생 증명서발급", "증명서 발급 시스템", "https://gwangju-student-certificates.pages.dev/certificates"),
        Shortcut("kakao", "카카오톡 1:1 문의", "업무지원포털 상담 채널", "https://pf.kakao.com/_XExgxeX/chat")
    )
    private val hrShortcut = Shortcut("hr", "인사관리 시스템", "HR 시스템 바로가기", "https://gwangju-hr-migration.pages.dev/hr")
    private val inventoryShortcut = Shortcut("inventory", "재고관리", "재고 입·출고 관리", "https://gwangju-inventory.pages.dev/")

    private val root = SettingsPage(ROOT, "설정") { host ->
        val notificationsOn = PortalNotifications.areEnabled(host.context)
        listOf(
            // 관리자 페이지로 바로 들어가는 버튼: 로그인 상태와 상관없이 항상 맨 위에 둡니다.
            adminHero(host),
            Row(
                id = "nav_notifications", title = "알림", kind = Kind.NAV,
                description = "기기 알림과 알림 종류",
                value = if (notificationsOn) "허용됨" else "꺼짐",
                tone = if (notificationsOn) Tone.GOOD else Tone.WARN,
                onClick = { host.navigate(NOTIFICATIONS) }
            ),
            Row(
                id = "nav_shortcuts", title = "바로가기", kind = Kind.NAV,
                description = "법정의무교육, 발주, 증명서, 문의",
                onClick = { host.navigate(SHORTCUTS) }
            ),
            Row(
                id = "nav_admin", title = "관리자 메뉴", kind = Kind.NAV,
                description = "게시글 휴지통, 공지 푸시, 연결 시스템",
                value = if (host.isAdmin) "로그인됨" else null,
                tone = Tone.GOOD,
                onClick = { host.navigate(ADMIN) }
            ),
            Row(
                id = "nav_app", title = "앱", kind = Kind.NAV,
                description = "새로고침, 캐시·세션 정리",
                onClick = { host.navigate(APP) }
            ),
            Row(
                id = "nav_info", title = "정보", kind = Kind.NAV,
                description = "앱 버전, 개인정보처리방침",
                onClick = { host.navigate(INFO) }
            )
        )
    }

    private val notifications = SettingsPage(NOTIFICATIONS, "알림") { host ->
        val context = host.context
        val enabled = PortalNotifications.areEnabled(context)
        val push = org.json.JSONObject(PortalPush.stateJson(context))
        val items = mutableListOf<SettingsItem>()

        items += Header("기기 알림")
        items += Row(
            id = "notif_status", title = "알림 상태", kind = Kind.INFO,
            description = if (enabled) {
                "이 기기에서 업무지원포털 앱의 알림 표시가 허용되어 있습니다."
            } else {
                "기기 알림이 꺼져 있습니다. [알림 허용]을 눌러 권한을 켜 주세요."
            },
            value = if (enabled) "허용됨" else "꺼짐",
            tone = if (enabled) Tone.GOOD else Tone.WARN
        )
        if (!enabled) {
            items += Row(
                id = "notif_allow", title = "알림 허용", kind = Kind.ACTION,
                onClick = { host.requestNotificationPermission() }
            )
        }
        items += Row(
            id = "notif_test", title = "테스트 알림", kind = Kind.ACTION,
            description = "기기에 알림이 표시되는지 확인",
            onClick = { host.sendTestNotification() }
        )
        items += Row(
            id = "notif_system", title = "시스템 알림 설정 열기", kind = Kind.ACTION,
            description = "알림 종류별 소리·표시는 기기 설정에서 조절",
            onClick = { host.openSystemNotificationSettings() }
        )

        if (!push.optBoolean("supported")) {
            items += Note("이 앱 버전은 푸시 수신을 지원하지 않습니다. 새 공지는 포털 공지사항에서 확인해 주세요.")
            return@SettingsPage items
        }

        val permitted = PortalPush.permittedTypes(context).toSet()
        val types = push.optJSONObject("types")
        fun switches(title: String, kinds: List<PushKind>) {
            val visible = kinds.filter { it.type in permitted }
            if (visible.isEmpty()) return
            items += Header(title)
            visible.forEach { kind ->
                items += Row(
                    id = "push_${kind.type}", title = kind.title, description = kind.description,
                    kind = Kind.SWITCH,
                    checked = types?.optBoolean(kind.type, true) ?: true,
                    enabled = !host.busy,
                    onToggle = { on ->
                        // 새 값은 바로 기기에 기록되므로, 잠금 후 다시 그리면 바뀐 스위치가 보입니다.
                        // 저장에 실패하면 setTypeEnabled 가 이전 값으로 되돌려 놓습니다.
                        PortalPush.setTypeEnabled(context, kind.type, on) { saved ->
                            if (!saved) host.toast("알림 설정을 저장하지 못했습니다. 네트워크 연결을 확인한 뒤 다시 시도해 주세요.")
                            host.setBusy(false)
                        }
                        host.setBusy(true)
                    }
                )
            }
        }
        switches("업무 알림", workKinds)
        switches("관리자 알림", adminKinds)

        items += Header("발주 알림")
        items += Row(
            id = "notif_order_link", title = "발주 알림 기기 연결", kind = Kind.LINK,
            description = "다른 기기에서 접수한 발주의 알림도 받기 (이메일 인증)",
            onClick = { host.requestMainAction(SettingsContract.ACTION_OPEN_URL, ORDER_NOTIFY_URL) }
        )

        if (!push.optBoolean("serverReady", true)) {
            items += Note("포털 서버의 알림 발송이 아직 준비되지 않아 알림이 오지 않을 수 있습니다. 관리자에게 문의해 주세요.")
        }
        when (push.optString("registerError")) {
            "limit" -> items += Note("알림을 받을 수 있는 기기 수가 가득 차 이 기기를 등록하지 못했습니다. 관리자에게 문의해 주세요.")
            "busy" -> items += Note("요청이 많아 알림 등록이 잠시 미뤄졌습니다. 잠시 후 자동으로 다시 시도합니다.")
        }
        items += Note(
            "발주·업무요청·증명서 알림은 이 앱에서 제출한 요청에 대해 전달되며, 발주 알림은 기기를 연결한 이메일로 접수한 발주도 포함합니다. " +
                "알림에는 요청 내용이 포함되지 않습니다." +
                if (permitted.any { it in adminKinds.map(PushKind::type) }) " 관리자 알림은 관리자로 로그인한 기기에만 전달됩니다." else ""
        )
        items
    }

    private val shortcutsPage = SettingsPage(SHORTCUTS, "바로가기") { host ->
        val all = if (host.isAdmin) shortcuts + hrShortcut + inventoryShortcut else shortcuts
        listOf<SettingsItem>(Header("빠른 업무 바로가기")) + all.map { shortcut ->
            Row(
                id = "shortcut_${shortcut.key}", title = shortcut.title, description = shortcut.description,
                kind = Kind.LINK,
                onClick = {
                    host.requestMainAction(SettingsContract.ACTION_OPEN_URL, host.link(shortcut.key) ?: shortcut.fallback)
                }
            )
        }
    }

    /** 관리자 페이지로 들어가는 요약 카드. 포털 세션이 끊겼어도 눌러서 로그인 화면으로 갈 수 있게 항상 같은 주소를 엽니다. */
    private fun adminHero(host: SettingsHost): SettingsItem.Hero {
        val roleLabel = when (PortalPush.role(host.context)) {
            "super_admin" -> "통합 관리자"
            "admin" -> "일반 관리자"
            else -> "관리자"
        }
        val loggedIn = host.isAdmin
        return SettingsItem.Hero(
            eyebrow = "ADMIN",
            title = "관리자 페이지",
            iconRes = R.drawable.ic_lucide_shield_check,
            description = if (loggedIn) {
                "관리자로 로그인되어 있습니다. 게시글·알림·시스템 관리는 관리자 페이지에서 합니다."
            } else {
                "관리자 인증 후 게시글·알림·시스템을 관리할 수 있습니다."
            },
            chip = if (loggedIn) roleLabel else "로그아웃",
            chipTone = if (loggedIn) Tone.GOOD else Tone.NEUTRAL,
            primaryLabel = if (loggedIn) "관리자 페이지 열기" else "관리자 로그인",
            onPrimary = { host.requestMainAction(SettingsContract.ACTION_OPEN_URL, BuildConfig.PORTAL_BASE_URL + "admin") }
        )
    }

    private val admin = SettingsPage(ADMIN, "관리자") { host ->
        val items = mutableListOf<SettingsItem>(adminHero(host))
        if (!host.isAdmin) {
            items += Note("관리자로 로그인하면 의무교육 링크, Slack 알림, 공지 푸시, 게시글 휴지통 같은 관리 기능이 여기에 나타납니다.")
            return@SettingsPage items
        }

        items += Header("관리 기능")
        items += SettingsItem.Tiles.of(
            listOf(
                SettingsItem.Tile(
                    id = "admin_edu", title = "의무교육 URL", description = "수강 링크 설정",
                    iconRes = R.drawable.ic_lucide_graduation_cap,
                    onClick = { host.requestMainAction(SettingsContract.ACTION_ADMIN_EDU) }
                ),
                SettingsItem.Tile(
                    id = "admin_slack", title = "Slack 알림", description = "지원요청 채널 연동",
                    iconRes = R.drawable.ic_lucide_message_square,
                    onClick = { host.requestMainAction(SettingsContract.ACTION_ADMIN_SLACK) }
                ),
                SettingsItem.Tile(
                    id = "admin_push", title = "공지 푸시", description = "발송 현황·재전송",
                    iconRes = R.drawable.ic_lucide_bell,
                    onClick = { host.requestMainAction(SettingsContract.ACTION_ADMIN_PUSH) }
                ),
                SettingsItem.Tile(
                    id = "admin_trash", title = "게시글 휴지통", description = "삭제된 게시글 복구",
                    iconRes = R.drawable.ic_lucide_trash,
                    onClick = { host.requestMainAction(SettingsContract.ACTION_ADMIN_TRASH) }
                )
            )
        )

        items += Header("연결된 시스템")
        items += SettingsItem.Tiles.of(
            listOf(
                SettingsItem.Tile(
                    id = "admin_hr", title = hrShortcut.title, description = hrShortcut.description,
                    iconRes = R.drawable.ic_lucide_users,
                    onClick = {
                        host.requestMainAction(SettingsContract.ACTION_OPEN_URL, host.link(hrShortcut.key) ?: hrShortcut.fallback)
                    }
                ),
                SettingsItem.Tile(
                    id = "admin_inventory", title = inventoryShortcut.title, description = inventoryShortcut.description,
                    iconRes = R.drawable.ic_lucide_package,
                    onClick = {
                        host.requestMainAction(
                            SettingsContract.ACTION_OPEN_URL,
                            host.link(inventoryShortcut.key) ?: inventoryShortcut.fallback
                        )
                    }
                )
            )
        )

        items += Header("계정")
        items += SettingsItem.ActionButton(
            id = "admin_logout", label = "관리자 로그아웃", iconRes = R.drawable.ic_lucide_log_out, danger = true,
            onClick = { host.requestMainAction(SettingsContract.ACTION_ADMIN_LOGIN) }
        )
        items
    }

    private val app = SettingsPage(APP, "앱") { host ->
        listOf(
            Header("앱 편의 기능"),
            Row(
                id = "app_reload", title = "포털 새로고침", description = "포털 메인으로 이동해 최신 데이터 반영", kind = Kind.ACTION,
                onClick = { host.requestMainAction(SettingsContract.ACTION_RELOAD) }
            ),
            Row(
                id = "app_clear", title = "캐시·세션 정리", description = "오류 해결 및 재접속", kind = Kind.ACTION,
                danger = true,
                onClick = {
                    host.confirm("임시 캐시와 로그인 세션을 정리하고 포털을 다시 시작할까요?") {
                        host.requestMainAction(SettingsContract.ACTION_CLEAR_SESSION)
                    }
                }
            )
        )
    }

    private val info = SettingsPage(INFO, "정보") { host ->
        PortalPush.refreshVerification(host.context)
        listOfNotNull(
            Header("앱 정보"),
            Row(id = "info_version", title = "앱 버전", kind = Kind.INFO, value = "v${BuildConfig.VERSION_NAME}"),
            Row(
                id = "info_device", title = "기기 번호", kind = Kind.ACTION,
                description = "외부 접속 승인 요청 시 관리자에게 알려 주세요. 누르면 복사됩니다.",
                value = PortalPush.deviceIdShort(host.context),
                onClick = { host.copyToClipboard("기기 번호", PortalPush.deviceIdShort(host.context)) }
            ),
            PortalPush.verificationLabel(host.context)?.let {
                Row(id = "info_verify", title = "포털 인증", kind = Kind.INFO, value = it,
                    description = "사내 네트워크에서 인증하면 앱을 닫아도 로그인이 유지됩니다. 외부에서 인증한 기기는 관리자 승인 후 유지됩니다.")
            },
            Row(
                id = "info_privacy", title = "개인정보처리방침", kind = Kind.LINK,
                onClick = { host.requestMainAction(SettingsContract.ACTION_OPEN_URL, PRIVACY_URL) }
            ),
            Note(
                "사내 네트워크 밖에서 접속할 때는 관리자 승인 전까지 앱을 닫으면 다시 로그인해야 합니다. " +
                    "앱을 삭제하면 기기 번호가 새로 만들어져 다시 승인받아야 합니다."
            ),
            Note("요리광주 업무지원포털 Android 앱\n글꼴: Pretendard (SIL Open Font License 1.1)")
        )
    }

    private val pages = listOf(root, notifications, shortcutsPage, admin, app, info).associateBy { it.id }

    fun page(id: String): SettingsPage = pages[id] ?: root
}
