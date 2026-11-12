package kr.or.kcaa.gwangju.portal.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kr.or.kcaa.gwangju.portal.R
import kr.or.kcaa.gwangju.portal.databinding.ActivitySettingsBinding
import kr.or.kcaa.gwangju.portal.push.PortalNotifications
import kr.or.kcaa.gwangju.portal.push.PortalPush
import org.json.JSONObject

/**
 * 앱 설정 화면. 웹 페이지(WebView)와 무관하게 열리는 별도 화면입니다.
 *
 * 화면은 [SettingsCatalog] 가 만든 항목 목록만 그립니다. 하위 화면은 이 액티비티 안에서 목록을 바꿔 보여 주고
 * 뒤로 가기로 한 단계씩 돌아갑니다. 웹을 움직여야 하는 동작은 결과로 메인 화면에 돌려줍니다. ([SettingsContract])
 */
class SettingsActivity : AppCompatActivity(), SettingsHost {

    private lateinit var binding: ActivitySettingsBinding
    private val adapter = SettingsAdapter()

    /** 열려 있는 화면 ID 스택 (맨 끝이 현재 화면) */
    private val pageStack = ArrayDeque<String>()

    private var links: JSONObject = JSONObject()

    override val context: Context get() = this
    override var isAdmin: Boolean = false
        private set
    override var busy: Boolean = false
        private set

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        PortalPush.sync(this)
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        isAdmin = intent.getBooleanExtra(SettingsContract.EXTRA_IS_ADMIN, false)
        links = runCatching { JSONObject(intent.getStringExtra(SettingsContract.EXTRA_LINKS_JSON).orEmpty()) }
            .getOrDefault(JSONObject())

        val saved = savedInstanceState?.getStringArrayList(STATE_STACK)
        pageStack.addAll(if (saved.isNullOrEmpty()) listOf(SettingsCatalog.ROOT) else saved)

        binding.settingsList.layoutManager = LinearLayoutManager(this)
        binding.settingsList.adapter = adapter
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        applySystemBarInsets()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pageStack.size > 1) {
                    pageStack.removeLast()
                    refresh()
                } else {
                    finish()
                }
            }
        })
        refresh()
    }

    /** 상태바·내비게이션 바 영역을 피해 상단 바와 목록 여백을 맞춥니다. */
    private fun applySystemBarInsets() {
        val bottomExtra = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.settingsTopBar.setPadding(bars.left, bars.top, bars.right, 0)
            binding.settingsList.setPadding(bars.left, 0, bars.right, bars.bottom + bottomExtra)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        // 권한·등록·역할이 바뀌면 알림 화면을 다시 그립니다. (호출 스레드는 정해져 있지 않음)
        PortalPush.stateListener = { runOnUiThread { if (!isFinishing) refresh() } }
    }

    override fun onStop() {
        PortalPush.stateListener = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 시스템 설정에서 알림을 바꾸고 돌아온 경우를 반영
        PortalPush.sync(this)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_STACK, ArrayList(pageStack))
    }

    // ───────────────────────── SettingsHost ─────────────────────────

    override fun link(key: String): String? = links.optString(key).takeIf { it.isNotBlank() }

    override fun navigate(pageId: String) {
        pageStack.addLast(pageId)
        refresh()
    }

    override fun refresh() {
        val page = SettingsCatalog.page(pageStack.last())
        binding.tvSettingsTitle.text = page.title
        adapter.submit(page.build(this))
    }

    override fun setBusy(busy: Boolean) {
        runOnUiThread {
            this.busy = busy
            if (!isFinishing && !isDestroyed) refresh()
        }
    }

    override fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun requestMainAction(action: String, url: String?) {
        val result = Intent().putExtra(SettingsContract.EXTRA_ACTION, action)
        if (url != null) result.putExtra(SettingsContract.EXTRA_URL, url)
        setResult(RESULT_OK, result)
        finish()
    }

    override fun requestNotificationPermission() {
        if (PortalNotifications.requestPermission(this, notificationPermissionLauncher)) refresh()
    }

    override fun sendTestNotification() {
        PortalNotifications.sendTest(this, notificationPermissionLauncher)
    }

    override fun openSystemNotificationSettings() {
        PortalNotifications.openSystemSettings(this)
    }

    override fun confirm(message: String, onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.settings_confirm) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private companion object {
        const val STATE_STACK = "settings_page_stack"
    }
}
