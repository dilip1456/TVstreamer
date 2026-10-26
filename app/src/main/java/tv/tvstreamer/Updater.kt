package tv.tvstreamer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
        val currentName = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
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
                val remoteName = tag.removePrefix("v").removePrefix("build-")
                val remoteCode = remoteName.substringBefore(".").toIntOrNull() ?: remoteName.toIntOrNull() ?: 0
                // Prefer name match; fall back to code. Don't reinstall same version.
                if (remoteName == currentName || remoteCode <= currentCode) {
                    return@withContext "Up to date ($currentName / code $currentCode)"
                }

                val file = File(ctx.cacheDir, "update.apk")
                val dReq = Request.Builder().url(downloadUrl).build()
                http.newCall(dReq).execute().use { dr ->
                    if (!dr.isSuccessful) return@withContext "Download failed: ${dr.code}"
                    file.outputStream().use { out -> dr.body?.byteStream()?.copyTo(out) }
                }

                // Prefer PackageInstaller (more reliable on TV), fall back to Intent
                try {
                    installWithPackageInstaller(ctx, file)
                } catch (e: Exception) {
                    installWithIntent(ctx, file)
                }
                "Downloaded $tag. Confirm the install prompt on screen."
            }
        } catch (e: Exception) {
            "Update error: ${e.message}"
        }
    }

    private fun installWithPackageInstaller(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        apk.inputStream().use { input ->
            session.openWrite("tvstreamer", 0, apk.length()).use { out ->
                input.copyTo(out)
                session.fsync(out)
            }
        }
        val intent = Intent(ctx, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        session.commit(pending.intentSender)
        session.close()
    }

    private fun installWithIntent(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "tv.tvstreamer.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
        }
        ctx.startActivity(intent)
    }
}
