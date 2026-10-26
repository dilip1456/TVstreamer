package tv.tvstreamer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

object Updater {
    private val http = OkHttpClient()
    private const val REPO = "dilip1456/TVstreamer"
    private const val APK_NAME = "tvstreamer.apk"

    suspend fun checkAndUpdate(ctx: Context, currentCode: Int): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext "Update check failed: ${r.code}"
                val json = JSONObject(r.body?.string() ?: "{}")
                val tag = json.optString("tag_name", "")
                val assets = json.optJSONArray("assets") ?: return@withContext "No assets"
                var downloadUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == APK_NAME) {
                        downloadUrl = a.optString("browser_download_url")
                        break
                    }
                }
                if (downloadUrl.isNullOrEmpty()) return@withContext "No APK in latest release"
                // Parse version from tag like build-3 or just compare tag number
                val remote = tag.removePrefix("build-").toIntOrNull() ?: 0
                if (remote <= currentCode) return@withContext "Up to date (v$currentCode)"
                // Download
                val file = File(ctx.cacheDir, "update.apk")
                val dReq = Request.Builder().url(downloadUrl).build()
                http.newCall(dReq).execute().use { dr ->
                    if (!dr.isSuccessful) return@withContext "Download failed: ${dr.code}"
                    file.outputStream().use { out -> dr.body?.byteStream()?.copyTo(out) }
                }
                // Install
                val uri = FileProvider.getUriForFile(ctx, "tv.tvstreamer.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    }
                }
                ctx.startActivity(intent)
                "Updating to $tag..."
            }
        } catch (e: Exception) {
            "Update error: ${e.message}"
        }
    }
}
