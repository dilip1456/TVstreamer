package tv.tvstreamer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * In-app update row (not a popup): download / install progress, Ask-once Update / Not now,
 * a short "Updated" notice, or the rare Android confirm hint.
 */
@Composable
fun UpdateBanner(store: Store, ctx: android.content.Context, modifier: Modifier = Modifier) {
    val pending = Updater.pending
    val progress = Updater.progress
    val busy = Updater.busy
    val status = Updater.status
    val kind = UpdateLogic.banner(progress, busy, pending != null, status)
    if (kind == UpdateLogic.Banner.HIDDEN) return

    val primary = remember { FocusRequester() }
    LaunchedEffect(kind, pending?.code) {
        if (kind == UpdateLogic.Banner.OFFER) {
            delay(80)
            runCatching { primary.requestFocus() }
        } else if (kind == UpdateLogic.Banner.SUCCESS || kind == UpdateLogic.Banner.MESSAGE) {
            delay(if (kind == UpdateLogic.Banner.SUCCESS) 4_000 else 8_000)
            Updater.clearNotice()
        }
    }

    val title = when (kind) {
        UpdateLogic.Banner.PROGRESS -> if (progress in 0..100) "Downloading update… $progress%" else "Downloading update…"
        UpdateLogic.Banner.INSTALLING -> status.ifBlank { "Installing…" }
        UpdateLogic.Banner.OFFER -> "Update ${pending?.label ?: ""} is ready"
        UpdateLogic.Banner.SUCCESS -> status.ifBlank { "Updated." }
        UpdateLogic.Banner.CONFIRM -> status.ifBlank { "Confirm the update on the screen (Android requires this once)." }
        UpdateLogic.Banner.MESSAGE -> status
        UpdateLogic.Banner.HIDDEN -> ""
    }
    val showBar = kind == UpdateLogic.Banner.PROGRESS || kind == UpdateLogic.Banner.INSTALLING

    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clipBar()
            .background(Pal.Surface)
            .border(1.dp, Pal.Outline, RoundedCornerShape(Radii.row))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Pal.Text, fontSize = Ts.ROW.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            if (showBar) {
                LinearProgressIndicator(
                    progress = { if (kind == UpdateLogic.Banner.INSTALLING) 1f else (progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Pal.Gold, trackColor = Pal.Dim.copy(alpha = 0.3f)
                )
            } else if (kind == UpdateLogic.Banner.OFFER) {
                Text("Install this version on the device.", color = Pal.Dim, fontSize = Ts.CAPTION.sp, maxLines = 1)
            }
        }
        if (kind == UpdateLogic.Banner.OFFER && pending != null) {
            PillButton("Update", { Updater.approve(ctx, store) },
                Modifier.focusRequester(primary).defaultMinSize(minHeight = 44.dp), primary = true, size = Ts.HEAD, compact = true)
            PillButton("Not now", { Updater.decline(store) },
                Modifier.defaultMinSize(minHeight = 44.dp), size = Ts.HEAD, compact = true)
        }
    }
}

private fun Modifier.clipBar() = this.clip(RoundedCornerShape(Radii.row))
