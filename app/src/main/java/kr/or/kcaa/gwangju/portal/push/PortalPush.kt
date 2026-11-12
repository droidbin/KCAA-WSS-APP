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
 * 네이티브 푸시(FCM) 공통 처리. 서버 계약은 포털 알림 허브(`/api/push/device`, `/api/push/preferences`)를 따릅니다.
 *
 * - 기기 식별자(deviceId): 앱이 만든 무작위 값. 포털 계열 도메인에 쿠키([DEVICE_COOKIE])로 심어,
 *   서버가 "이 기기에서 낸 발주·업무요청·증명서 수정요청"과 "관리자 로그인한 기기"를 알 수 있게 합니다.
 *   서버는 해시만 저장하므로 원문을 로그에 남기지 않습니다.
 * - FCM 토큰과 알림 종류별 수신 설정은 포털 `/api/push/device` 에 등록합니다.
 * - 서버에 기기가 남아 있는지, 관리자 역할이 유지되는지는 `/api/push/preferences`(GET)로 확인합니다.
 * - `app/google-services.json` 없이 빌드하면 Firebase 가 초기화되지 않으며, 이때 푸시는 꺼진 상태로 동작합니다.
 */
object PortalPush {

    const val DEVICE_COOKIE = "kcaa_app_device"

    /** 알림 채널: 공지 / 발주 / 업무요청 / 증명서 / 관리자 */
    const val CHANNEL_NOTICE = "kcaa_notice"
    private const val CHANNEL_ORDER = "kcaa_order"
    private const val CHANNEL_SUPPORT = "kcaa_support"
    private const val CHANNEL_CERTIFICATE = "kcaa_certificate"
    private const val CHANNEL_ADMIN = "kcaa_admin"

    /** v1.5.0 까지 쓰던 단일 채널 (채널을 나누면서 삭제) */
    private const val LEGACY_CHANNEL_ID = "kcaa_portal_notice_channel"

    private const val TYPE_NOTICE = "notice"

    /** 권한 계층: 일반직원 < 일반관리자 < 통합관리자 */
    private val ROLE_LEVEL = mapOf("staff" to 0, "admin" to 1, "super_admin" to 2)
    private const val DEFAULT_ROLE = "staff"

    /**
     * 알림 종류와 그 설정을 다룰 수 있는 최소 권한 (portal_custom.js 의 PUSH_SECTIONS 와 같은 값).
     * 공지 / 발주 처리상태 / 업무요청 답변 / 증명서 수정요청 결과 / 새 발주 접수 / 새 증명서 수정요청
     */
    private val TYPE_MIN_ROLE = linkedMapOf(
        TYPE_NOTICE to "staff",
        "order" to "staff",
        "support" to "staff",
        "certificate" to "staff",
        "orderAdmin" to "admin",
        "certificateAdmin" to "super_admin"
    )

    private val TYPE_CHANNEL = mapOf(
        TYPE_NOTICE to CHANNEL_NOTICE,
        "order" to CHANNEL_ORDER,
        "support" to CHANNEL_SUPPORT,
        "certificate" to CHANNEL_CERTIFICATE,
        "orderAdmin" to CHANNEL_ADMIN,
        "certificateAdmin" to CHANNEL_ADMIN
    )

    val TYPES: List<String> = TYPE_MIN_ROLE.keys.toList()

    fun hasPermission(userRole: String, requiredRole: String): Boolean =
        (ROLE_LEVEL[userRole] ?: 0) >= (ROLE_LEVEL[requiredRole] ?: Int.MAX_VALUE)

    private const val TAG = "PortalPush"
    private const val PREFS = "portal_push_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TOKEN = "fcm_token"
    private const val KEY_SYNC_HASH = "sync_hash"
    private const val KEY_SYNC_AT = "sync_at"
    private const val KEY_SERVER_READY = "server_ready"
    private const val KEY_ROLE = "role"
    private const val KEY_TYPE_PREFIX = "type_"
    private const val KEY_STATE_AT = "server_state_at"
    private const val KEY_RETRY_AT = "register_retry_at"
    private const val KEY_REGISTER_ERROR = "register_error"
    private const val RESYNC_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    private const val STATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val RATE_LIMIT_WAIT_MS = 10L * 60 * 1000
    private const val REGISTER_PATH = "api/push/device"
    private const val STATE_PATH = "api/push/preferences"

    /** 등록 오류 표시값: 기기 수 한도(503) / 요청 제한(429) */
    private const val ERROR_LIMIT = "limit"
    private const val ERROR_BUSY = "busy"

    private val executor = Executors.newSingleThreadExecutor()

    /** 권한·등록 상태가 바뀌었을 때 호출됩니다. (호출 스레드는 정해져 있지 않음) */
    @Volatile
    var stateListener: (() -> Unit)? = null

    private fun notifyStateChanged() {
        stateListener?.invoke()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Firebase 설정(google-services.json)이 포함된 빌드인지 여부 */
    fun isAvailable(context: Context): Boolean = try {
        FirebaseApp.getApps(context.applicationContext).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /** 알림 종류에 맞는 채널. 모르는 종류는 공지 채널로 표시합니다. */
    fun channelFor(type: String): String = TYPE_CHANNEL[type] ?: CHANNEL_NOTICE

    /** 수신 설정을 따를 알림 종류. 모르는 종류는 공지 설정을 따릅니다. */
    fun settingTypeFor(type: String): String = if (type in TYPE_MIN_ROLE) type else TYPE_NOTICE

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            Triple(CHANNEL_NOTICE, R.string.notif_channel_notice, R.string.notif_channel_notice_desc),
            Triple(CHANNEL_ORDER, R.string.notif_channel_order, R.string.notif_channel_order_desc),
            Triple(CHANNEL_SUPPORT, R.string.notif_channel_support, R.string.notif_channel_support_desc),
            Triple(CHANNEL_CERTIFICATE, R.string.notif_channel_certificate, R.string.notif_channel_certificate_desc),
            Triple(CHANNEL_ADMIN, R.string.notif_channel_admin, R.string.notif_channel_admin_desc)
        )
        channels.forEach { (id, name, description) ->
            manager.createNotificationChannel(
                NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    this.description = context.getString(description)
                }
            )
        }
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
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

    /** 이 기기 사용자의 권한. 포털 관리자 세션으로 확인하며, 세션이 끝나도 서버가 관리자 역할을 유지하는 동안은 남습니다. */
    fun role(context: Context): String =
        prefs(context).getString(KEY_ROLE, DEFAULT_ROLE)?.takeIf { it in ROLE_LEVEL } ?: DEFAULT_ROLE

    /**
     * 설정 화면이 포털 관리자 세션으로 확인한 권한을 알려 줍니다.
     * 관리자였던 기기가 일반직원으로 확인되면(세션 만료 또는 로그아웃) 서버에 관리자 역할이 남아 있는지 물어,
     * 남아 있으면(로그인 후 30일) 관리자 알림 설정을 유지하고 로그아웃으로 해제됐으면 일반직원으로 내립니다.
     */
    fun setRole(context: Context, role: String) {
        val appContext = context.applicationContext
        val current = role(appContext)
        if (role !in ROLE_LEVEL || role == current) return
        if (role != DEFAULT_ROLE) {
            prefs(appContext).edit().putString(KEY_ROLE, role).apply()
            sync(appContext) { notifyStateChanged() }
            return
        }
        executor.execute {
            val keepsAdmin = fetchServerState(appContext)?.optBoolean("admin", false) == true
            if (keepsAdmin) {
                notifyStateChanged()
            } else {
                prefs(appContext).edit().putString(KEY_ROLE, DEFAULT_ROLE).apply()
                sync(appContext) { notifyStateChanged() }
            }
        }
    }

    /** 현재 권한으로 다룰 수 있는 알림 종류 */
    fun permittedTypes(context: Context): List<String> {
        val userRole = role(context)
        return TYPES.filter { type -> hasPermission(userRole, TYPE_MIN_ROLE.getValue(type)) }
    }

    /**
     * 알림 종류별 수신 설정을 바꾸고 포털에 저장합니다.
     * 저장에 실패하면 이전 값으로 되돌리고 [onResult] 에 false 를 전달합니다. (호출 스레드는 정해져 있지 않음)
     */
    fun setTypeEnabled(context: Context, type: String, enabled: Boolean, onResult: (Boolean) -> Unit = {}) {
        if (type !in permittedTypes(context)) {
            onResult(false)
            return
        }
        val previous = isTypeEnabled(context, type)
        prefs(context).edit().putBoolean(KEY_TYPE_PREFIX + type, enabled).apply()
        sync(context) { saved ->
            if (!saved) prefs(context).edit().putBoolean(KEY_TYPE_PREFIX + type, previous).apply()
            onResult(saved)
        }
    }

    /** 설정 화면에 표시할 상태(JSON 문자열). 권한 밖의 알림 종류는 넣지 않습니다. */
    fun stateJson(context: Context): String = JSONObject().apply {
        val store = prefs(context)
        put("supported", isAvailable(context))
        // 마지막 기기 등록 때 서버가 알림 발송 준비가 되어 있었는지 여부
        put("serverReady", store.getBoolean(KEY_SERVER_READY, true))
        // 마지막 기기 등록이 막힌 이유: "limit"(기기 수 한도) / "busy"(요청 제한) / ""(없음)
        put("registerError", store.getString(KEY_REGISTER_ERROR, "").orEmpty())
        put("role", role(context))
        put("types", JSONObject().apply {
            permittedTypes(context).forEach { type -> put(type, isTypeEnabled(context, type)) }
        })
    }.toString()

    fun onNewToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
        sync(context)
    }

    /** 토큰·수신 설정을 포털에 등록합니다. 내용이 같고 최근에 등록했으면 건너뜁니다. */
    fun sync(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        if (!isAvailable(appContext)) {
            onResult?.invoke(false)
            return
        }
        try {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (token.isNullOrBlank()) {
                        onResult?.invoke(false)
                    } else {
                        prefs(appContext).edit().putString(KEY_TOKEN, token).apply()
                        executor.execute {
                            val saved = register(appContext, token)
                            onResult?.invoke(saved)
                        }
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "token unavailable: ${error.message}")
                    onResult?.invoke(false)
                }
        } catch (e: Exception) {
            Log.w(TAG, "sync skipped: ${e.message}")
            onResult?.invoke(false)
        }
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(BuildConfig.PORTAL_BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "KCAAWssAndroid/${BuildConfig.VERSION_NAME}")
        }

    /**
     * 서버에 저장된 이 기기의 상태(`{device, kind, admin, types}`)를 읽습니다. 읽지 못하면 null.
     * 서버가 기기를 지웠으면(FCM 토큰 만료 등) `{"device": false}` 가 옵니다.
     */
    private fun fetchServerState(context: Context): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(STATE_PATH, "GET").apply {
                setRequestProperty("Cookie", "$DEVICE_COOKIE=${deviceId(context)}")
            }
            if (connection.responseCode !in 200..299) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "device state unavailable: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun setRegisterError(context: Context, error: String) {
        val store = prefs(context)
        if (store.getString(KEY_REGISTER_ERROR, "") == error) return
        store.edit().putString(KEY_REGISTER_ERROR, error).apply()
        notifyStateChanged()
    }

    /** 포털에 저장되었거나 이미 같은 내용이 저장되어 있으면 true */
    private fun register(context: Context, token: String): Boolean {
        val payload = JSONObject().apply {
            put("deviceId", deviceId(context))
            put("token", token)
            put("platform", "android")
            put("appVersion", BuildConfig.VERSION_NAME)
            put("notificationsEnabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
            // 권한 밖의 알림 종류는 보내지 않습니다.
            put("types", JSONObject().apply {
                permittedTypes(context).forEach { type -> put(type, isTypeEnabled(context, type)) }
            })
        }.toString()

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val store = prefs(context)
        val now = System.currentTimeMillis()
        val recentlySynced = now - store.getLong(KEY_SYNC_AT, 0L) < RESYNC_INTERVAL_MS
        if (hash == store.getString(KEY_SYNC_HASH, null) && recentlySynced) {
            // 같은 내용이 등록되어 있어도, 서버가 기기를 지웠으면 다시 등록합니다. (가끔만 확인)
            val lastCheck = store.getLong(KEY_STATE_AT, 0L)
            if (now - lastCheck in 0 until STATE_CHECK_INTERVAL_MS) return true
            val state = fetchServerState(context) ?: return true
            store.edit().putLong(KEY_STATE_AT, now).apply()
            if (state.optBoolean("device", true)) return true
        }

        // 요청 제한(429)을 받은 뒤에는 잠시 기다립니다.
        if (now < store.getLong(KEY_RETRY_AT, 0L)) return false

        var connection: HttpURLConnection? = null
        return try {
            connection = open(REGISTER_PATH, "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status in 200..299) {
                val answer = connection.inputStream.bufferedReader().use { it.readText() }
                // 서버에 FCM 발송 설정이 없으면 등록은 되어도 알림이 오지 않습니다.
                val serverReady = runCatching { JSONObject(answer).optBoolean("configured", true) }
                    .getOrDefault(true)
                val editor = store.edit().putBoolean(KEY_SERVER_READY, serverReady)
                if (serverReady) {
                    editor.putString(KEY_SYNC_HASH, hash).putLong(KEY_SYNC_AT, now).putLong(KEY_STATE_AT, now)
                } else {
                    // 등록 완료로 기록하지 않아, 서버가 준비되면 다음 실행 때 다시 확인합니다.
                    Log.w(TAG, "portal push delivery is not configured on the server")
                }
                editor.apply()
                setRegisterError(context, "")
                true
            } else {
                Log.w(TAG, "device registration failed: HTTP $status")
                when (status) {
                    429 -> {
                        store.edit().putLong(KEY_RETRY_AT, now + RATE_LIMIT_WAIT_MS).apply()
                        setRegisterError(context, ERROR_BUSY)
                    }
                    503 -> setRegisterError(context, ERROR_LIMIT)
                }
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "device registration failed: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }
}
