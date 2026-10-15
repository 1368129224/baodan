package com.example.policyvault.domain.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.policyvault.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long
)

class AppUpdater(private val context: Context) {

    companion object {
        private const val GITHUB_OWNER = "1368129224"
        private const val GITHUB_REPO = "baodan"
        private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    }

    /**
     * 检查 GitHub Releases 最新版本
     */
    suspend fun checkUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        val url = URL(API_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "PolicyVault-Android")
            connectTimeout = 8000
            readTimeout = 8000
        }

        try {
            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("检查更新失败 (HTTP $responseCode)")
            }

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonObj = JSONObject(jsonStr)

            val tagName = jsonObj.optString("tag_name", "")
            val name = jsonObj.optString("name", tagName)
            val body = jsonObj.optString("body", "")

            var downloadUrl = ""
            var apkSize = 0L

            val assets = jsonObj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (downloadUrl.isEmpty()) {
                throw IllegalStateException("最新版本未提供 APK 安装包")
            }

            // 判断是否有新版本：比对当前版本与远端版本
            val currentCommit = BuildConfig.GIT_COMMIT_HASH

            // 只要远端 tagName 或 release 描述中未包含当前 commit，即代表存在更新版本
            val hasUpdate = !tagName.contains(currentCommit) && !body.contains(currentCommit)

            UpdateInfo(
                hasUpdate = hasUpdate,
                latestVersion = if (name.isNotBlank()) name else tagName,
                releaseNotes = body,
                downloadUrl = downloadUrl,
                apkSize = apkSize
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载 APK 并通过回调返回下载百分比 (0~100)
     */
    suspend fun downloadApk(downloadUrl: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        var currentUrl = downloadUrl
        var redirectCount = 0
        var conn: HttpURLConnection

        while (true) {
            val url = URL(currentUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "PolicyVault-Android")
            }

            val code = conn.responseCode
            if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308) && redirectCount < 5) {
                currentUrl = conn.getHeaderField("Location")
                conn.disconnect()
                redirectCount++
            } else {
                break
            }
        }

        val totalLength = conn.contentLength.toLong()
        val downloadDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(downloadDir, "PolicyVault-update.apk")
        if (apkFile.exists()) apkFile.delete()

        conn.inputStream.use { input ->
            FileOutputStream(apkFile).use { output ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                var totalRead = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalLength > 0) {
                        val progress = ((totalRead * 100) / totalLength).toInt().coerceIn(0, 100)
                        onProgress(progress)
                    }
                }
            }
        }

        apkFile
    }

    /**
     * 调起系统 PackageInstaller 安装已下载的 APK
     */
    fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
