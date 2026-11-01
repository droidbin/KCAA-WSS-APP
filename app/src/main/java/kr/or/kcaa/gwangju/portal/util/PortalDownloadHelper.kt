package kr.or.kcaa.gwangju.portal.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import kr.or.kcaa.gwangju.portal.R

/**
 * WebView 내부 첨부파일 다운로드(쿠키 및 세션 유지 포함)를 처리하는 헬퍼.
 */
object PortalDownloadHelper {

    fun enqueueDownload(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                setMimeType(mimeType)

                // 포털 로그인 세션 쿠키를 DownloadManager에 전달하여 인증된 첨부파일 다운로드 보장
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookie)
                }
                if (!userAgent.isNullOrBlank()) {
                    addRequestHeader("User-Agent", userAgent)
                }

                setDescription(context.getString(R.string.app_name))
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(context, R.string.msg_download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, R.string.msg_download_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
