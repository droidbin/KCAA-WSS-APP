package kr.or.kcaa.gwangju.portal.push

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kr.or.kcaa.gwangju.portal.MainActivity
import kr.or.kcaa.gwangju.portal.R

/**
 * 기기 알림 권한·시스템 설정·테스트 알림처럼 화면(액티비티)이 함께 쓰는 알림 동작.
 * 메인 화면과 설정 화면이 같은 코드를 쓰도록 모아 둡니다.
 */
object PortalNotifications {

    private const val PREFS_NAME = "portal_app_prefs"
    private const val KEY_NOTIF_ASKED = "notification_permission_asked"
    private const val TEST_NOTIFICATION_ID = 1001

    fun areEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 알림 권한 요청 (Android 13+). 사용자가 [알림 허용]을 눌렀을 때 호출합니다.
     * - 처음 요청이거나 다시 물어볼 수 있으면 시스템 권한 대화상자 표시
     * - 영구 거부 상태(또는 Android 12 이하에서 꺼짐)면 시스템 알림 설정 화면으로 이동
     * @return 이미 켜져 있으면 true
     */
    fun requestPermission(activity: Activity, launcher: ActivityResultLauncher<String>): Boolean {
        if (areEnabled(activity)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val askedBefore = prefs.getBoolean(KEY_NOTIF_ASKED, false)
            val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.POST_NOTIFICATIONS
            )
            if (!askedBefore || canAskAgain) {
                prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return false
            }
        }
        openSystemSettings(activity)
        return false
    }

    /** 첫 실행 때 한 번만 권한을 묻습니다. 이미 물었거나 허용돼 있으면 아무것도 하지 않습니다. */
    fun askOnFirstRun(activity: Activity, launcher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!PortalPush.isAvailable(activity) || areEnabled(activity)) return
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIF_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun openSystemSettings(context: Context) {
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
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.msg_notif_settings_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 기기에 테스트 알림을 표시합니다. 알림이 꺼져 있으면 안내 후 권한 요청으로 이어 줍니다. */
    fun sendTest(activity: Activity, launcher: ActivityResultLauncher<String>) {
        if (!areEnabled(activity)) {
            Toast.makeText(activity, R.string.msg_notif_disabled, Toast.LENGTH_SHORT).show()
            requestPermission(activity, launcher)
            return
        }
        PortalPush.ensureChannels(activity)
        val intent = Intent(activity, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            activity,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(activity, PortalPush.CHANNEL_NOTICE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(ContextCompat.getColor(activity, R.color.portal_accent))
            .setContentTitle(activity.getString(R.string.app_loading_title))
            .setContentText(activity.getString(R.string.notif_test_text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(activity).notify(TEST_NOTIFICATION_ID, notification)
            Toast.makeText(activity, R.string.msg_notif_test_sent, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(activity, R.string.msg_notif_disabled, Toast.LENGTH_SHORT).show()
        }
    }
}
