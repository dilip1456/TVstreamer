package tv.tvstreamer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updater: checks the latest GitHub release, downloads tvstreamer.apk into app cache with
 * progress, and installs through a PackageInstaller session (not ACTION_VIEW / ACTION_INSTALL_PACKAGE,
 * which opened the system installer on every launch).
 *
 * True silent install needs the app to be a device owner (or similarly privileged). On a normal Fire TV
 * or phone, REQUEST_INSTALL_PACKAGES + "Install unknown apps" is enough to self-update; Android may
 * still show one system confirm (USER_ACTION_NOT_REQUIRED is honoured when the OS allows it, API 31+).
 * Auto (the default) downloads and installs in the background, then shows a short "Updated" toast
 * and in-app row. Ask-once shows the same banner with Update / Not now (never a popup dialog).
 * A dismissed version is not offered again until a newer release or a manual check.
 * Android may still show one system confirm when the OS requires it (API 31+ USER_ACTION_NOT_REQUIRED
 * is used whenever the platform allows a silent package replace).
 */
object Updater {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private const val REPO = "dilip1456/TVstreamer"
    private const val APK_NAME = "tvstreamer.apk"
    const val ACTION_SESSION = "tv.tvstreamer.UPDATE_SESSION"
    private const val EXTRA_TAG = "tag"

    data class Pending(val code: Long, val label: String, val file: File)

    var status by mutableStateOf("")
        private set
    var progress by mutableStateOf(-1)   // 0..100 while downloading, else -1
        private set
    var pending by mutableStateOf<Pending?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    fun installedCode(ctx: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    } catch (e: Exception) { 0L }

    fun installedName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    private fun apkCode(ctx: Context, f: File): Long {
        if (!f.exists() || f.length() < 100_000) return -1
        @Suppress("DEPRECATION")
        val info = runCatching { ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0) }.getOrNull() ?: return -1
        if (info.packageName != ctx.packageName) return -1
        return PackageInfoCompat.getLongVersionCode(info)
    }

    fun cleanup(ctx: Context) {
        val cur = installedCode(ctx)
        runCatching { File(ctx.cacheDir, "update.apk").delete() }
        dir(ctx).listFiles()?.forEach { f -> if (f.name.endsWith(".part") || apkCode(ctx, f) <= cur) f.delete() }
    }

    fun statusLine(): String = when {
        progress in 0..99 -> "Downloading update… $progress%"
        progress == 100 && busy -> "Installing…"
        else -> status
    }

    /** Launch: check and, in Ask-once, only prompt when a newer version has not been declined. */
    suspend fun onLaunch(ctx: Context, store: Store): String = check(ctx, store, manual = false)

    suspend fun check(ctx: Context, store: Store, manual: Boolean): String {
        if (busy) return status.ifEmpty { "Already checking…" }
        busy = true
        progress = -1
        pending = null
        status = "Checking for updates…"
        val msg = try {
            withContext(Dispatchers.IO) { checkIo(ctx.applicationContext, store, manual) }
        } catch (e: Exception) {
            "Update error: ${e.message ?: e.javaClass.simpleName}"
        }
        status = msg
        busy = false
        if (progress < 100) progress = -1
        return msg
    }

    fun clearNotice() {
        if (pending != null || busy || progress in 0..100) return
        status = ""
    }

    fun decline(store: Store) {
        pending?.let { store.updateDeclinedCode = it.code }
        pending = null
        status = "Update skipped. It will not be offered again until a newer version."
    }

    fun approve(ctx: Context, store: Store) {
        val p = pending ?: return
        pending = null
        store.updateDeclinedCode = 0
        status = install(ctx, p.file, p.label, interactive = true)
    }

    private fun checkIo(ctx: Context, store: Store, manual: Boolean): String {
        cleanup(ctx)
        val cur = installedCode(ctx)
        val curName = installedName(ctx)
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val json = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return "Update check failed (HTTP ${r.code})"
            JSONObject(r.body?.string() ?: "{}")
        }
        val tag = json.optString("tag_name", "")
        val code = UpdateLogic.remoteCode(json.optString("name", ""), tag)
        val assets = json.optJSONArray("assets") ?: return "Update: latest release has no files"
        var url = ""
        var size = -1L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == APK_NAME) { url = a.optString("browser_download_url"); size = a.optLong("size", -1); break }
        }
        if (url.isEmpty()) return "Update: no $APK_NAME in latest release"
        val label = tag.ifEmpty { "v$code" }
        val mode = store.updateMode
        when (UpdateLogic.decide(cur, code, store.updateDeclinedCode, mode, manual)) {
            UpdateLogic.Decision.UP_TO_DATE -> return "Up to date (v$curName, code $cur)"
            UpdateLogic.Decision.SKIP_DECLINED -> return ""
            UpdateLogic.Decision.OFFER, UpdateLogic.Decision.AUTO_INSTALL -> { }
        }
        val file = download(ctx, url, size, code) ?: return status.ifEmpty { "Update download failed" }
        val fileCode = apkCode(ctx, file)
        if (fileCode < 0) { file.delete(); return "Update: downloaded file is not a valid " + ctx.getString(R.string.app_name) + " APK" }
        if (fileCode <= cur) { file.delete(); return "Up to date (v$curName, code $cur)" }
        val ready = Pending(fileCode, label, file)
        return if (mode == UpdateLogic.Mode.AUTO) {
            install(ctx, file, label, interactive = manual)
        } else {
            pending = ready
            "Update $label is ready."
        }
    }

    private fun download(ctx: Context, url: String, size: Long, code: Long): File? {
        val file = File(dir(ctx), "tvstreamer-$code.apk")
        val have = apkCode(ctx, file)
        if (have >= code && (size <= 0 || file.length() == size)) return file
        file.delete()
        val part = File(dir(ctx), file.name + ".part")
        progress = 0
        val ok = http.newCall(Request.Builder().url(url).build()).execute().use { dr ->
            if (!dr.isSuccessful) { status = "Update download failed (HTTP ${dr.code})"; return@use false }
            val total = if (size > 0) size else dr.body?.contentLength() ?: -1L
            var received = 0L
            part.outputStream().use { out ->
                val input = dr.body?.byteStream() ?: return@use
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    received += n
                    progress = UpdateLogic.progress(received, total)
                }
            }
            true
        }
        if (!ok) { part.delete(); return null }
        if (!part.renameTo(file)) { part.delete(); status = "Update: could not save the download"; return null }
        progress = 100
        return file
    }

    private fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" screen when needed. Returns a message if the user must grant it first. */
    private fun needUnknownApps(ctx: Context, tag: String, interactive: Boolean): String? {
        if (canInstall(ctx)) return null
        if (!interactive) return "Update $tag is ready. Allow \"Install unknown apps\" for " + ctx.getString(R.string.app_name) + ", then Check for updates."
        val opened = runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        val app = ctx.getString(R.string.app_name)
        return if (opened) "Turn on \"Allow from this source\" for $app, then Check for updates again."
        else "Update $tag downloaded. Allow \"Install unknown apps\" for $app in the device settings, then Check for updates again."
    }

    private fun install(ctx: Context, file: File, tag: String, interactive: Boolean): String {
        needUnknownApps(ctx, tag, interactive)?.let { return it }
        return try {
            commitSession(ctx, file, tag)
            "Installing $tag…"
        } catch (e: Exception) {
            "Update $tag downloaded but the installer could not start: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun commitSession(ctx: Context, file: File, tag: String) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("package", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, UpdateInstallReceiver::class.java).setAction(ACTION_SESSION).putExtra(EXTRA_TAG, tag)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
    }

    fun onSessionStatus(ctx: Context, intent: Intent) {
        val statusCode = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val tag = intent.getStringExtra(EXTRA_TAG).orEmpty().ifEmpty { "update" }
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (statusCode) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(confirm) }
                    Handler(Looper.getMainLooper()).post { status = "Confirm $tag on the screen (one time)." }
                } else {
                    Handler(Looper.getMainLooper()).post { this.status = "Update $tag needs a one-time confirm, but the installer screen was missing." }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Handler(Looper.getMainLooper()).post {
                progress = -1
                pending = null
                status = "Updated to $tag."
                runCatching { Toast.makeText(ctx, "Updated to $tag", Toast.LENGTH_SHORT).show() }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> Handler(Looper.getMainLooper()).post {
                this.status = "Update $tag cancelled."
            }
            else -> Handler(Looper.getMainLooper()).post {
                this.status = "Update $tag failed" + (msg?.let { ": $it" } ?: ".")
            }
        }
    }
}
