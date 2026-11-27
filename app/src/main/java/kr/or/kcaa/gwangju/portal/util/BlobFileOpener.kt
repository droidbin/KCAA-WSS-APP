package kr.or.kcaa.gwangju.portal.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import kr.or.kcaa.gwangju.portal.BuildConfig
import kr.or.kcaa.gwangju.portal.R
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 웹이 window.open(blob:...) 으로 여는 파일(수료증·증명서 PDF 등)을 앱에서 보여 줍니다.
 *
 * blob 주소는 그것을 만든 페이지 안에서만 읽을 수 있어 외부 앱이나 DownloadManager 로 넘길 수 없습니다.
 * 그래서 메인 WebView 의 JS 로 blob 을 읽어 브릿지(receiveBlobFile)로 받고, 앱 캐시에 저장한 뒤
 * FileProvider 로 기기의 뷰어(PDF 뷰어 등)에 넘깁니다.
 *
 * 브릿지 호출은 앱이 직접 발급한 1회용 토큰이 있을 때만 받습니다. (웹이 임의로 파일을 밀어 넣지 못하게)
 */
object BlobFileOpener {

    private const val SHARED_DIR = "shared"
    private const val MAX_BYTES = 30 * 1024 * 1024
    private const val TOKEN_TTL_MS = 2 * 60 * 1000L

    private val pendingTokens = ConcurrentHashMap<String, Long>()

    /** blob 주소의 원래 출처가 포털 허용 도메인인지 (blob:https://host/uuid) */
    fun isTrustedBlob(uri: Uri?): Boolean {
        if (uri == null || !uri.scheme.equals("blob", ignoreCase = true)) return false
        return PortalHosts.isTrusted(uri.schemeSpecificPart)
    }

    /** 메인 WebView 에서 blob 을 읽어 브릿지로 넘기는 스크립트를 실행합니다. */
    fun request(webView: WebView, blobUri: Uri, bridgeName: String) {
        val context = webView.context
        if (!isTrustedBlob(blobUri)) {
            Toast.makeText(context, R.string.msg_file_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        pendingTokens.entries.removeIf { now - it.value > TOKEN_TTL_MS }
        val token = UUID.randomUUID().toString()
        pendingTokens[token] = now

        val urlJs = JSONObject.quote(blobUri.toString())
        val tokenJs = JSONObject.quote(token)
        val script = """
            (function(u,t){
              var b=window.$bridgeName; if(!b||!b.receiveBlobFile) return;
              fetch(u).then(function(r){ if(!r.ok) throw new Error(); return r.blob(); })
                .then(function(blob){ return new Promise(function(res,rej){
                  var fr=new FileReader(); fr.onload=function(){res([fr.result, blob.type||''])};
                  fr.onerror=rej; fr.readAsDataURL(blob); }); })
                .then(function(v){ b.receiveBlobFile(t, v[0], v[1]); })
                .catch(function(){ b.receiveBlobFile(t, '', ''); });
            })($urlJs,$tokenJs);
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    /**
     * 브릿지 스레드에서 호출됩니다. 토큰을 확인하고 파일을 저장한 뒤, 열 Intent 를 만들 수 있으면 돌려줍니다.
     * 실패하면 null.
     */
    fun saveFromBridge(context: Context, token: String, dataUrl: String, mimeType: String): Uri? {
        if (pendingTokens.remove(token) == null) return null
        if (dataUrl.isEmpty()) return null
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) return null
        // base64 4글자 = 3바이트
        if ((dataUrl.length - comma) / 4L * 3L > MAX_BYTES) return null

        val bytes = runCatching { Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT) }
            .getOrNull() ?: return null

        val mime = normalizeMime(mimeType, bytes)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        // 이전에 연 파일은 정리 (뷰어가 읽고 있을 수 있어 1시간 지난 것만)
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }

        val file = File(dir, "portal_${System.currentTimeMillis()}.$ext")
        return runCatching {
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        }.getOrNull()
    }

    fun mimeOf(context: Context, uri: Uri): String =
        context.contentResolver.getType(uri) ?: "application/octet-stream"

    /** 저장한 파일을 기기의 뷰어로 엽니다. (UI 스레드) */
    fun open(context: Context, contentUri: Uri?) {
        if (contentUri == null) {
            Toast.makeText(context, R.string.msg_file_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, mimeOf(context, contentUri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.msg_file_no_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun normalizeMime(mimeType: String, bytes: ByteArray): String {
        val declared = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val isPdfMagic = bytes.size >= 4 &&
            bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()
        return when {
            isPdfMagic -> "application/pdf"
            declared.isNotEmpty() && declared != "application/octet-stream" -> declared
            else -> "application/octet-stream"
        }
    }
}
