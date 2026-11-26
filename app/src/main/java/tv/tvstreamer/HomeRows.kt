@file:SuppressLint("RestrictedApi")   // tvprovider 1.0.0 builder setters are wrongly flagged

package tv.tvstreamer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's row on the TV home screen (Android TV / Google TV preview channel + "Play next", and Fire TV where the
 * launcher shows sideloaded apps' channels): Continue watching, recent live channels and Recommended, built from
 * local data only (watch history, saved lists, cached AI suggestions) — never a portal call.
 *
 * Everything is guarded: phones, devices without the TV provider and any provider error simply skip.
 * Published when the app goes to the background (that is when the home screen is visible).
 */
object HomeRows {
    private const val PREFS = "home_rows"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var supportedCache: Boolean? = null

    fun supported(ctx: Context): Boolean = supportedCache ?: runCatching {
        Build.VERSION.SDK_INT >= 26 &&
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            ctx.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null
    }.getOrDefault(false).also { supportedCache = it }

    private val amazon: Boolean get() = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

    /** Republish soon (debounced); no-op when unsupported. */
    fun syncSoon(ctx: Context, repo: Repo, delayMs: Long = 1500) {
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT < 26 || !supported(app)) return
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            runCatching { sync(app, repo) }
        }
    }

    /** Local data only. Nothing is published while adult content is shown (privacy on a shared TV). */
    private fun entries(repo: Repo): List<HomeRowsPlan.Entry> {
        if (repo.showAdult) return emptyList()
        val cont = repo.store.continueRecords()
        val live = repo.store.list("recent").filter { repo.listOk(it.locked) }.map { repo.fresh(it) }
        val rec = Ai.suggested.ifEmpty {
            runCatching { heroPicks(repo, "home").map { it.show } }.getOrDefault(emptyList())
        }.filter { repo.listOk(it.locked) && "title:${it.key}" !in repo.hidden && "vod:${it.cat}" !in repo.hidden }
        return HomeRowsPlan.build(cont, live, rec)
    }

    @RequiresApi(26)
    private fun sync(ctx: Context, repo: Repo) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val helper = PreviewChannelHelper(ctx)
        val list = entries(repo)
        val sig = HomeRowsPlan.signature(list)
        var chId = prefs.getLong("channel", -1L)
        val chOk = chId > 0 && runCatching { helper.getPreviewChannel(chId) != null }.getOrDefault(false)
        if (chOk && sig == prefs.getString("sig", null)) return

        if (!chOk) {
            chId = runCatching { helper.publishChannel(channel(ctx)) }.getOrDefault(-1L)
            if (chId <= 0) return
            prefs.edit().putLong("channel", chId).putString("programs", "").apply()
            // The first (default) channel of an app is shown without asking on Android TV; ignored elsewhere.
            runCatching { TvContractCompat.requestChannelBrowsable(ctx, chId) }
        }

        // Replace the previous cards (ids we inserted) with the current ones.
        ids(prefs, "programs").forEach { id -> runCatching { helper.deletePreviewProgram(id) } }
        ids(prefs, "watchnext").forEach { id -> runCatching { ctx.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) } }

        val programs = ArrayList<Long>()
        val watchNext = ArrayList<Long>()
        list.forEachIndexed { n, e ->
            runCatching { helper.publishPreviewProgram(preview(ctx, chId, e, list.size - n)) }.getOrNull()?.takeIf { it > 0 }?.let { programs += it }
            if (e.section == HomeRowsPlan.Section.CONTINUE)
                runCatching { helper.publishWatchNextProgram(watchNextOf(ctx, e)) }.getOrNull()?.takeIf { it > 0 }?.let { watchNext += it }
        }
        prefs.edit()
            .putString("programs", programs.joinToString(","))
            .putString("watchnext", watchNext.joinToString(","))
            .putString("sig", sig)
            .apply()
    }

    private fun ids(prefs: android.content.SharedPreferences, k: String): List<Long> =
        prefs.getString(k, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    private fun launchIntent(ctx: Context): Intent =
        (ctx.packageManager.getLeanbackLaunchIntentForPackage(ctx.packageName) ?: ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: Intent(ctx, MainActivity::class.java))

    private fun channel(ctx: Context): PreviewChannel {
        val b = PreviewChannel.Builder()
            .setDisplayName(ctx.getString(R.string.app_name))
            .setAppLinkIntentUri(Uri.parse(launchIntent(ctx).toUri(Intent.URI_INTENT_SCHEME)))
        runCatching { ctx.getDrawable(R.mipmap.ic_launcher)?.toBitmap(320, 320)?.let { b.setLogo(it) } }
        return b.build()
    }

    private fun itemIntent(ctx: Context, e: HomeRowsPlan.Entry): Uri {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(HomeRowsPlan.link(e.item, e.play)))
            .setClass(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Uri.parse(i.toUri(Intent.URI_INTENT_SCHEME))
    }

    private fun art(ctx: Context, i: Item): Uri =
        if (i.img.startsWith("http")) Uri.parse(i.img)
        else Uri.parse("android.resource://${ctx.packageName}/${R.mipmap.ic_launcher}")

    private fun title(i: Item): String =
        if (i.kind == "itv" || i.isEpisodeLike()) i.name else runCatching { i.parsed.title.ifBlank { i.name } }.getOrDefault(i.name)

    private fun subtitle(e: HomeRowsPlan.Entry): String = when (e.section) {
        HomeRowsPlan.Section.CONTINUE -> listOf("Continue watching", e.item.season.takeIf { e.item.isEpisode() }.orEmpty(),
            HomeRowsPlan.leftText(e.pos, e.dur)).filter { it.isNotBlank() }.joinToString(" · ")
        HomeRowsPlan.Section.LIVE -> "Live TV"
        HomeRowsPlan.Section.RECOMMENDED -> listOf("Recommended", e.item.year.take(4), e.item.genre).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun preview(ctx: Context, chId: Long, e: HomeRowsPlan.Entry, weight: Int): PreviewProgram {
        val i = e.item
        val live = i.kind == "itv"
        val type = when {
            live -> TvContractCompat.PreviewPrograms.TYPE_CHANNEL
            amazon -> TvContractCompat.PreviewPrograms.TYPE_CLIP          // the types Fire TV's launcher accepts
            i.isEpisode() -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
            i.isSeries -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
            else -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
        }
        val b = PreviewProgram.Builder()
            .setChannelId(chId)
            .setType(type)
            .setTitle(title(i))
            .setDescription(subtitle(e))
            .setPosterArtUri(art(ctx, i))
            .setPosterArtAspectRatio(if (live) TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
            .setIntentUri(itemIntent(ctx, e))
            .setInternalProviderId(i.key)
            .setWeight(weight)
            .setLive(live)
        if (e.section == HomeRowsPlan.Section.CONTINUE && e.dur > 0) {
            b.setLastPlaybackPositionMillis(e.pos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            b.setDurationMillis(e.dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return b.build()
    }

    private fun watchNextOf(ctx: Context, e: HomeRowsPlan.Entry): WatchNextProgram {
        val i = e.item
        val b = WatchNextProgram.Builder()
            .setType(if (i.isEpisode()) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(if (e.at > 0) e.at else System.currentTimeMillis())
            .setTitle(title(i))
            .setDescription(subtitle(e))
            .setPosterArtUri(art(ctx, i))
            .setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_MOVIE_POSTER)
            .setIntentUri(itemIntent(ctx, e))
            .setInternalProviderId(i.key)
        if (e.dur > 0) {
            b.setLastPlaybackPositionMillis(e.pos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            b.setDurationMillis(e.dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return b.build()
    }
}

/** A card on the TV home screen was opened: the App composable resolves it once the saved lists are loaded. */
object DeepLinkBus {
    var pending by mutableStateOf<HomeRowsPlan.Link?>(null)
}
