package tv.tvstreamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives PackageInstaller session status (including the one-time confirm intent). */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Updater.onSessionStatus(ctx.applicationContext, intent)
    }
}
