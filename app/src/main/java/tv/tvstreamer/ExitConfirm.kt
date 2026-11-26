package tv.tvstreamer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Bluey-styled "Exit Bluey?" confirm. Cancel is focused by default. Back cancels once the
 * opening press has been ignored for [BackNav.EXIT_ARM_MS], so a held or repeated Back
 * cannot confirm or skip the dialog.
 */
@Composable
fun ExitConfirm() {
    val ctx = LocalContext.current
    val cancelFr = remember { FocusRequester() }
    val title = "Exit ${stringResource(R.string.app_name)}?"
    LaunchedEffect(Unit) { delay(60); runCatching { cancelFr.requestFocus() } }
    PopupFrame(
        onClose = {
            val now = android.os.SystemClock.elapsedRealtime()
            if (BackNav.exitArmed(now, AppExit.openedAt)) AppExit.cancel()
        },
        alignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(min = 280.dp, max = 400.dp)
                .background(Pal.Surface, RoundedCornerShape(Radii.panel))
                .border(1.dp, Pal.Outline, RoundedCornerShape(Radii.panel))
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BlueyMark(size = 48.dp)
            Spacer(Modifier.height(16.dp))
            Text(title, color = Pal.Text, fontSize = Ts.TITLE.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Cancel", { AppExit.cancel() }, Modifier.focusRequester(cancelFr), size = Ts.HEAD)
                PillButton("Exit", { AppExit.cancel(); finishApp(ctx) }, primary = true, size = Ts.HEAD)
            }
        }
    }
}
