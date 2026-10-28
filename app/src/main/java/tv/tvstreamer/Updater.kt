package tv.tvstreamer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks the latest GitHub release and installs it.
 *
 * Old bugs fixed here:
 *  - a leftover cache/update.apk made it say "already downloaded" forever and never install;
 *  - the PackageInstaller session sent its "confirm install" step back to MainActivity, which ignored it,
 *    so no install prompt ever appeared;
 *  - no check for the "Install unknown apps" permission.
 * Now: compare version codes, (re)download into cache/updates, verify the APK, and open the system
 * installer through the FileProvider.
 */
object Updater {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private const val REPO = "dilip1456/TVstreamer"
    private const val APK_NAME = "tvstreamer.apk"
    private const val AUTHORITY = "tv.tvstreamer.fileprovider"

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    private fun installedCode(ctx: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (e: Exception) { 0L }

    private fun installedName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /** Version code of an APK file, or -1 when it is not a valid TVStreamer APK. */
    private fun apkCode(ctx: Context, f: File): Long {
        if (!f.exists() || f.length() < 100_000) return -1
        @Suppress("DEPRECATION")
        val info = runCatching { ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0) }.getOrNull() ?: return -1
        if (info.packageName != ctx.packageName) return -1
        return PackageInfoCompat.getLongVersionCode(info)
    }

    /** "TVStreamer 1.36 (code 36)" -> 36. Falls back to the tag "v1.36" -> 36 (the workflow uses code == minor). */
    private fun remoteCode(name: String, tag: String): Long {
        Regex("code\\s*(\\d+)", RegexOption.IGNORE_CASE).find(name)?.let { return it.groupValues[1].toLong() }
        val parts = tag.removePrefix("v").removePrefix("build-").split(".")
        return parts.getOrNull(1)?.toLongOrNull() ?: parts.getOrNull(0)?.toLongOrNull() ?: -1
    }

    /** Removes downloaded APKs that are not newer than the installed app (e.g. after a successful update). */
    fun cleanup(ctx: Context) {
        val cur = installedCode(ctx)
        runCatching { File(ctx.cacheDir, "update.apk").delete() } // file used by old versions
        dir(ctx).listFiles()?.forEach { f -> if (f.name.endsWith(".part") || apkCode(ctx, f) <= cur) f.delete() }
    }

    /**
     * @param interactive true when the user pressed Update (we may open the permission screen).
     * Returns a message to show on screen; starting with "Up to date" means nothing to do.
     */
    suspend fun checkAndUpdate(ctx: Context, @Suppress("UNUSED_PARAMETER") currentCode: Int = 0, interactive: Boolean = true): String =
        withContext(Dispatchers.IO) {
            val cur = installedCode(ctx)
            val curName = installedName(ctx)
            try {
                cleanup(ctx)
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val json = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext "Update check failed (HTTP ${r.code})"
                    JSONObject(r.body?.string() ?: "{}")
                }
                val tag = json.optString("tag_name", "")
                val code = remoteCode(json.optString("name", ""), tag)
                val assets = json.optJSONArray("assets") ?: return@withContext "Update: latest release has no files"
                var url = ""
                var size = -1L
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == APK_NAME) { url = a.optString("browser_download_url"); size = a.optLong("size", -1); break }
                }
                if (url.isEmpty()) return@withContext "Update: no $APK_NAME in latest release"
                if (code in 1..cur) return@withContext "Up to date (v$curName, code $cur)"

                val file = File(dir(ctx), "tvstreamer-${code}.apk")
                var fileCode = apkCode(ctx, file)
                if (fileCode <= cur || (size > 0 && file.length() != size)) {
                    // Missing, stale or partial: download again
                    file.delete()
                    val part = File(dir(ctx), file.name + ".part")
                    http.newCall(Request.Builder().url(url).build()).execute().use { dr ->
                        if (!dr.isSuccessful) return@withContext "Update download failed (HTTP ${dr.code})"
                        part.outputStream().use { out -> dr.body?.byteStream()?.copyTo(out) }
                    }
                    if (!part.renameTo(file)) return@withContext "Update: could not save the download"
                    fileCode = apkCode(ctx, file)
                }
                if (fileCode < 0) { file.delete(); return@withContext "Update: downloaded file is not a valid TVStreamer APK" }
                if (fileCode <= cur) { file.delete(); return@withContext "Up to date (v$curName, code $cur)" }

                withContext(Dispatchers.Main) { install(ctx, file, tag, interactive) }
            } catch (e: Exception) {
                "Update error: ${e.message ?: e.javaClass.simpleName}"
            }
        }

    private fun install(ctx: Context, file: File, tag: String, interactive: Boolean): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            if (!interactive) return "Update $tag is ready. Press Update to install it."
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                return "Update $tag downloaded. Allow \"Install unknown apps\" for TVStreamer in the device settings, then press Update again."
            }
            return "Update $tag downloaded. Turn on \"Allow from this source\" for TVStreamer, come back and press Update again."
        }
        val uri = FileProvider.getUriForFile(ctx, AUTHORITY, file)
        @Suppress("DEPRECATION")
        val intents = listOf(
            Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, ctx.packageName),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        )
        var err: Throwable? = null
        for (i in intents) {
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val r = runCatching { ctx.startActivity(i) }
            if (r.isSuccess) return "Installing $tag… confirm \"Install\" (or \"Update\") on the screen."
            err = r.exceptionOrNull()
        }
        return "Update $tag downloaded but the installer could not open: ${err?.message}"
    }
}
