package kr.or.kcaa.gwangju.portal.push

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kr.or.kcaa.gwangju.portal.BuildConfig
import kr.or.kcaa.gwangju.portal.MainActivity
import kr.or.kcaa.gwangju.portal.R
import kr.or.kcaa.gwangju.portal.util.PortalHosts

/**
 * 포털 서버가 보낸 FCM 데이터 메시지를 기기 알림으로 표시합니다.
 *
 * 메시지 data 형식: type(notice|order|support|certificate|orderAdmin|certificateAdmin|inventoryAdmin|deviceAdmin), title, body, url(선택), tag(선택)
 * 종류별 채널로 표시하고, 같은 tag 는 같은 알림을 덮어씁니다.
 * 알림을 누르면 url(포털·발주·증명서·재고관리 도메인만 허용)을 앱에서 엽니다.
 */
class PortalMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PortalPush.onNewToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data["type"].orEmpty()
        // 모르는 종류는 공지 설정·공지 채널을 따릅니다.
        if (!PortalPush.isTypeEnabled(this, PortalPush.settingTypeFor(type))) return

        val title = data["title"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.title
            ?: getString(R.string.app_loading_title)
        val body = data["body"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.body
            ?: getString(R.string.notif_default_text)
        // 알림이 여는 주소는 포털·발주·증명서·재고관리(https)만 허용하고, 그 외에는 포털 홈으로 대체합니다.
        val url = data["url"]?.takeIf { PortalHosts.isNotificationTarget(it) } ?: BuildConfig.PORTAL_BASE_URL
        val notificationId = (data["tag"] ?: "$type|$url|$title").hashCode()

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        PortalPush.ensureChannels(this)
        val notification = NotificationCompat.Builder(this, PortalPush.channelFor(type))
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(ContextCompat.getColor(this, R.color.portal_accent))
            .setContentTitle(title.take(80))
            .setContentText(body.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(200)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // 알림 권한이 꺼져 있으면 표시하지 않음
        }
    }
}
