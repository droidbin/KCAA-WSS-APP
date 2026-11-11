package kr.or.kcaa.gwangju.portal.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kr.or.kcaa.gwangju.portal.BuildConfig
import kr.or.kcaa.gwangju.portal.R
import kr.or.kcaa.gwangju.portal.util.PortalHosts
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * 네이티브 푸시(FCM) 공통 처리.
 *
 * - 기기 식별자(deviceId): 앱이 만든 무작위 값. 포털 계열 도메인에 쿠키([DEVICE_COOKIE])로 심어,
 *   서버가 "이 기기에서 낸 발주·업무요청·증명서 수정요청"과 "관리자 로그인한 기기"를 알 수 있게 합니다.
 * - FCM 토큰과 알림 종류별 수신 설정은 포털 `/api/push/device` 에 등록합니다.
 * - `app/google-services.json` 없이 빌드하면 Firebase 가 초기화되지 않으며, 이때 푸시는 꺼진 상태로 동작합니다.
 */
object PortalPush {

    const val CHANNEL_ID = "kcaa_portal_notice_channel"
    const val DEVICE_COOKIE = "kcaa_app_device"

    /** 알림 종류: 공지 / 발주(접수·처리상태) / 업무요청 답변 / 증명서 수정요청 승인 */
    val TYPES = listOf("notice", "order", "support", "certificate")

    private const val TAG = "PortalPush"
    private const val PREFS = "portal_push_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TOKEN = "fcm_token"
    private const val KEY_SYNC_HASH = "sync_hash"
    private const val KEY_SYNC_AT = "sync_at"
    private const val KEY_TYPE_PREFIX = "type_"
    private const val RESYNC_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    private const val REGISTER_PATH = "api/push/device"

    private val executor = Executors.newSingleThreadExecutor()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Firebase 설정(google-services.json)이 포함된 빌드인지 여부 */
    fun isAvailable(context: Context): Boolean = try {
        FirebaseApp.getApps(context.applicationContext).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    @Synchronized
    fun deviceId(context: Context): String {
        val store = prefs(context)
        store.getString(KEY_DEVICE_ID, null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val id = bytes.joinToString("") { "%02x".format(it) }
        store.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    /** 포털 계열 도메인에 기기 식별 쿠키를 심습니다. (쿠키 정리 후에도 다시 호출) */
    fun applyDeviceCookie(context: Context) {
        if (!isAvailable(context)) return
        val cookie = "$DEVICE_COOKIE=${deviceId(context)}; Path=/; Max-Age=31536000; Secure; HttpOnly; SameSite=Lax"
        val manager = CookieManager.getInstance()
        PortalHosts.hosts.forEach { host -> manager.setCookie("https://$host", cookie) }
        manager.flush()
    }

    fun isTypeEnabled(context: Context, type: String): Boolean =
        prefs(context).getBoolean(KEY_TYPE_PREFIX + type, true)

    fun setTypeEnabled(context: Context, type: String, enabled: Boolean) {
        if (type !in TYPES) return
        prefs(context).edit().putBoolean(KEY_TYPE_PREFIX + type, enabled).apply()
        sync(context)
    }

    /** 설정 화면에 표시할 상태(JSON 문자열) */
    fun stateJson(context: Context): String = JSONObject().apply {
        put("supported", isAvailable(context))
        put("types", JSONObject().apply {
            TYPES.forEach { type -> put(type, isTypeEnabled(context, type)) }
        })
    }.toString()

    fun onNewToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
        sync(context)
    }

    /** 토큰·수신 설정을 포털에 등록합니다. 내용이 같고 최근에 등록했으면 건너뜁니다. */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        if (!isAvailable(appContext)) return
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (!token.isNullOrBlank()) {
                        prefs(appContext).edit().putString(KEY_TOKEN, token).apply()
                        executor.execute { register(appContext, token) }
                    }
                }
                .addOnFailureListener { error -> Log.w(TAG, "token unavailable: ${error.message}") }
        } catch (e: Exception) {
            Log.w(TAG, "sync skipped: ${e.message}")
        }
    }

    private fun register(context: Context, token: String) {
        val payload = JSONObject().apply {
            put("deviceId", deviceId(context))
            put("token", token)
            put("platform", "android")
            put("appVersion", BuildConfig.VERSION_NAME)
            put("notificationsEnabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
            put("types", JSONObject().apply {
                TYPES.forEach { type -> put(type, isTypeEnabled(context, type)) }
            })
        }.toString()

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val store = prefs(context)
        val recentlySynced = System.currentTimeMillis() - store.getLong(KEY_SYNC_AT, 0L) < RESYNC_INTERVAL_MS
        if (hash == store.getString(KEY_SYNC_HASH, null) && recentlySynced) return

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(BuildConfig.PORTAL_BASE_URL + REGISTER_PATH).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "KCAAWssAndroid/${BuildConfig.VERSION_NAME}")
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status in 200..299) {
                store.edit()
                    .putString(KEY_SYNC_HASH, hash)
                    .putLong(KEY_SYNC_AT, System.currentTimeMillis())
                    .apply()
            } else {
                Log.w(TAG, "device registration failed: HTTP $status")
            }
        } catch (e: Exception) {
            Log.w(TAG, "device registration failed: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
}
