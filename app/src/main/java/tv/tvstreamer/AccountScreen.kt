package tv.tvstreamer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val ADIM = Color(0xFF9AA0B5)
private val AWARN = Color(0xFFFFB37A)

/** Settings > Google account & sync. */
@Composable
fun AccountScreen(repo: Repo, onReady: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var cid by remember { mutableStateOf(Cloud.clientId) }
    var secret by remember { mutableStateOf(Cloud.clientSecret) }
    var code by remember { mutableStateOf<Google.DeviceCode?>(null) }
    var note by remember { mutableStateOf("") }
    var signing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var secrets by remember { mutableStateOf(Cloud.syncSecrets) }

    fun link(url: String) {
        note = if (openUrl(ctx, url)) "Opened in the browser." else "No web browser on this device. On a phone or computer, open:  $url"
    }

    // Sign-in: get a code, show it, poll until approved. Leaving the screen cancels the polling.
    LaunchedEffect(signing) {
        if (!signing) return@LaunchedEffect
        try {
            keyboard?.hide()
            Cloud.clientId = cid; Cloud.clientSecret = secret
            val dc = withContext(Dispatchers.IO) { Cloud.startSignIn() }
            code = dc
            note = ""
            Cloud.waitForSignIn(dc)
            note = "Signed in. Your settings now sync with this Google account."
            // Fresh device: the portal login came from the cloud copy, go straight to the catalog
            if (Cloud.error.isEmpty() && repo.store.portal.isNotEmpty() && repo.store.mac.isNotEmpty()) onReady()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            note = e.message ?: "Sign-in failed"
        } finally { code = null; signing = false }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Label("Google account & sync", 32) }
        item {
            Label("Sign in with Google to keep favourites, hidden items, languages, sorting, Live TV settings, remote buttons, " +
                "Continue watching and Fix match choices the same on your TV and phone. They are saved in a private app folder " +
                "in your own Google Drive that only TVstreamer can see.", 14, ADIM, maxLines = 4)
        }
        if (note.isNotEmpty()) item { Label(note, 15, Pal.Gold, maxLines = 4) }

        if (Cloud.signedIn) {
            item {
                Column(Modifier.fillMaxWidth().background(Color(0xFF1A1A22), RoundedCornerShape(8.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label("Signed in as ${Cloud.email.ifEmpty { "(email not shared)" }}", 20, Color.White)
                    Label("Status: " + (if (Cloud.busy) "Syncing..." else Cloud.status.ifEmpty { "Signed in" }), 15, ADIM)
                    Label("Last synced: " + if (Cloud.lastSync > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(Cloud.lastSync)) else "never", 15, ADIM)
                    if (Cloud.error.isNotEmpty()) Label(Cloud.error, 15, AWARN, maxLines = 4)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row1("Sync now", { Cloud.syncNow(); note = "" }, Modifier.weight(1f))
                    Row1("Sign out", { Cloud.signOut(); note = "Signed out. Data on this device is kept." }, Modifier.weight(1f))
                }
            }
            item {
                Row1((if (secrets) "☑" else "☐") + "  Also sync portal login and API keys", {
                    secrets = !secrets; Cloud.changeSyncSecrets(secrets)
                    note = if (secrets) "Portal login and API keys will sync." else "Portal login and API keys will be removed from the cloud copy."
                })
            }
            item {
                Label("Portal URL, MAC, serial, locked-section password and TMDB/OMDb keys are kept in your private Google Drive " +
                    "app-data folder (only TVstreamer can read it), so a new device is ready right after sign-in. " +
                    "The Google client ID and secret below are never synced.", 13, ADIM, maxLines = 3)
            }
            item {
                if (!confirmDelete) Row1("Delete cloud data…", { confirmDelete = true })
                else Column(Modifier.fillMaxWidth().background(Color(0xFF2A1F1A), RoundedCornerShape(8.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Delete the synced copy from your Google Drive and sign this device out? Data on this device stays. " +
                        "Sign your other devices out first, or they will upload their copy again.", 15, AWARN, maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row1("Delete", { confirmDelete = false; scope.launch { note = "Deleting..."; note = Cloud.deleteCloud() } }, Modifier.weight(1f))
                        Row1("Cancel", { confirmDelete = false }, Modifier.weight(1f))
                    }
                }
            }
        } else {
            val c = code
            if (c != null) {
                item { SignInCode(c) { link(c.url) } }
                item { Row1("Cancel sign-in", { signing = false }) }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row1(if (signing) "Starting..." else "Sign in with Google", { if (!signing) signing = true }, Modifier.weight(1f))
                    }
                }
                if (Cloud.error.isNotEmpty()) item { Label(Cloud.error, 15, AWARN, maxLines = 3) }
            }
        }

        item { Label("Your Google sign-in client (one-time setup)", 18) }
        item {
            Label("Google needs a free \"OAuth client\" from your own Google Cloud project: create a project, enable the Google Drive API, " +
                "set up the OAuth consent screen (External, add your Gmail as a test user), then Credentials > Create credentials > " +
                "OAuth client ID > type \"TVs and Limited Input devices\". Copy its Client ID and Client secret here. " +
                "Use the same values on the TV and the phone. Full steps are in the README.", 13, ADIM, maxLines = 6)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SettingField("Google OAuth Client ID (…apps.googleusercontent.com)", cid) { cid = it } }
                Row1("Open Google Cloud ↗", { link(Google.CONSOLE_URL) }, Modifier.width(260.dp))
            }
        }
        item { SettingField("Google OAuth Client secret", secret, password = true) { secret = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row1("Save client", { keyboard?.hide(); Cloud.clientId = cid; Cloud.clientSecret = secret; note = "Saved" }, Modifier.weight(1f))
                Row1("Hide keyboard", { keyboard?.hide() }, Modifier.weight(1f))
            }
        }
        if (!Cloud.encrypted) item { Label("Note: this device could not encrypt the sign-in data; it is still private to the app.", 13, AWARN, maxLines = 2) }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun SignInCode(c: Google.DeviceCode, openLink: () -> Unit) {
    val qr = remember(c.url) { qrBitmap(c.url) }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1A1A22), RoundedCornerShape(8.dp)).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("On your phone or computer go to", 16, ADIM)
            Label(c.url.removePrefix("https://"), 26, Color.White)
            Label("and enter this code:", 16, ADIM)
            Text(c.userCode, color = Pal.Gold, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Label("Choose your Google account and allow access. This page updates by itself.", 14, ADIM, maxLines = 2)
            Row1("Open the page on this device ↗", openLink, Modifier.width(340.dp))
        }
        if (qr != null) Box(Modifier.background(Color.White, RoundedCornerShape(6.dp)).padding(8.dp)) {
            Image(qr, "QR code for ${c.url}", Modifier.size(170.dp), filterQuality = FilterQuality.None)
        }
    }
}

/** QR code image for [text] (black on white), or null if it cannot be made. */
fun qrBitmap(text: String): ImageBitmap? = runCatching {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 0))
    val bmp = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    bmp.asImageBitmap()
}.getOrNull()
