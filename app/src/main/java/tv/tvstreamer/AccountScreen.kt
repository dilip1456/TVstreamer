package tv.tvstreamer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
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

private val ADIM get() = Pal.Dim
private val AWARN get() = Pal.Warn

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

    var noteErr by remember { mutableStateOf<GoogleError?>(null) }
    val tv = rememberIsTv()
    val body = if (tv) 17 else 15

    fun link(url: String) {
        note = if (openUrl(ctx, url)) "Opened in the browser." else "No web browser on this device. On a phone or computer, open:  $url"
    }

    // Sign-in: get a code, show it, poll until approved. Leaving the screen cancels the polling.
    // If Google signs in without the Drive permission (box not ticked), explain and show a fresh code.
    LaunchedEffect(signing) {
        if (!signing) return@LaunchedEffect
        try {
            keyboard?.hide()
            Cloud.clientId = cid; Cloud.clientSecret = secret
            var attempts = 0
            while (true) {
                val dc = withContext(Dispatchers.IO) { Cloud.startSignIn() }
                code = dc
                if (attempts == 0) { note = ""; noteErr = null }
                if (dc.space == DriveSpace.FILE && attempts == 0)
                    note = "Google would not offer the hidden app folder to this client, so TVstreamer will keep one file " +
                        "(${Google.FILE_NAME}) in your Google Drive instead. It can only see files it created."
                try {
                    Cloud.waitForSignIn(dc); break
                } catch (e: GoogleError) {
                    if (e.code != "scope_missing" || ++attempts > 3) throw e
                    noteErr = e; note = ""
                }
            }
            noteErr = null
            note = "Signed in. Your settings now sync with this Google account."
            // Fresh device: the portal login came from the cloud copy, go straight to the catalog
            if (Cloud.error.isEmpty() && repo.store.portal.isNotEmpty() && repo.store.mac.isNotEmpty()) onReady()
        } catch (e: CancellationException) { throw e } catch (e: GoogleError) {
            noteErr = e; note = ""
        } catch (e: Exception) {
            note = "Sign-in failed: ${e.message ?: e.javaClass.simpleName}"
        } finally { code = null; signing = false }
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = if (AppUi.phoneNav) 16.dp else 40.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Label("Google account & sync", if (tv) 32 else 26, maxLines = 2)
        Label("Sign in with Google to keep favourites, hidden items, languages, sorting, Live TV settings, remote buttons, " +
            "Continue watching and Fix match choices the same on your TV and phone. They are saved in your own Google Drive, " +
            "in a place only TVstreamer can read.", body - 1, ADIM, maxLines = 20)
        if (note.isNotEmpty()) Label(note, body, Pal.GoldText, maxLines = 20)
        noteErr?.let { ErrorBox(it.message ?: "", it.hint, it.link, body) { u -> link(u) } }

        if (Cloud.signedIn) {
            Column(Modifier.fillMaxWidth().background(Pal.Field, RoundedCornerShape(8.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("Signed in as ${Cloud.email.ifEmpty { "(email not shared)" }}", body + 3, Pal.Text, maxLines = 3)
                Label("Status: " + (if (Cloud.busy) "Syncing..." else Cloud.status.ifEmpty { "Signed in" }), body, ADIM, maxLines = 4)
                Label("Last synced: " + if (Cloud.lastSync > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(Cloud.lastSync)) else "never", body, ADIM, maxLines = 2)
                Label("Stored in: " + if (Cloud.space == DriveSpace.APPDATA) "hidden app folder in your Google Drive" else "the file ${Google.FILE_NAME} in your Google Drive", body - 1, ADIM, maxLines = 3)
            }
            if (Cloud.error.isNotEmpty()) ErrorBox(Cloud.error, Cloud.errorHint, Cloud.errorLink, body) { u -> link(u) }
            Buttons(listOf("Sync now" to { Cloud.syncNow(); note = ""; noteErr = null },
                "Sign out" to { Cloud.signOut(); note = "Signed out. Data on this device is kept." }))
            Row1((if (secrets) "☑" else "☐") + "  Also sync portal login and API keys", {
                secrets = !secrets; Cloud.changeSyncSecrets(secrets)
                note = if (secrets) "Portal login and API keys will sync." else "Portal login and API keys will be removed from the cloud copy."
            })
            Label("Portal URL, MAC, serial, locked-section password and TMDB/OMDb keys are kept in your own Google Drive " +
                "(private to your account), so a new device is ready right after sign-in. " +
                "The Google client ID and secret below are never synced.", body - 2, ADIM, maxLines = 20)
            if (!confirmDelete) Row1("Delete cloud data…", { confirmDelete = true })
            else Column(Modifier.fillMaxWidth().background(Pal.WarnBox, RoundedCornerShape(8.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Delete the synced copy from your Google Drive and sign this device out? Data on this device stays. " +
                    "Sign your other devices out first, or they will upload their copy again.", body, AWARN, maxLines = 20)
                Buttons(listOf("Delete" to { confirmDelete = false; scope.launch { note = "Deleting..."; note = Cloud.deleteCloud() } },
                    "Cancel" to { confirmDelete = false }))
            }
        } else {
            val c = code
            if (c != null) {
                SignInCode(c, tv) { link(c.url) }
                Row1("Cancel sign-in", { signing = false })
            } else {
                Row1(if (signing) "Starting..." else "Sign in with Google", { if (!signing) signing = true })
                if (Cloud.error.isNotEmpty() && noteErr == null) ErrorBox(Cloud.error, Cloud.errorHint, Cloud.errorLink, body) { u -> link(u) }
            }
        }

        Spacer(Modifier.height(6.dp))
        Label("Your Google sign-in client (one-time setup)", body + 3, maxLines = 2)
        Label("Google needs a free \"OAuth client\" from your own Google Cloud project. Use the same Client ID and secret on the TV " +
            "and the phone. The checklist below lists every step; full steps are in the README.", body - 2, ADIM, maxLines = 20)
        FieldWithLink({ SettingField("Google OAuth Client ID (…apps.googleusercontent.com)", cid, multiLine = true) { cid = it } },
            "Open Google Cloud ↗") { link(Google.CONSOLE_URL) }
        SettingField("Google OAuth Client secret (starts with GOCSPX-)", secret, password = true) { secret = it }
        Row1("Save client", { keyboard?.hide(); Cloud.clientId = cid; Cloud.clientSecret = secret; note = "Saved" })
        if (!Cloud.encrypted) Label("Note: this device could not encrypt the sign-in data; it is still private to the app.", body - 2, AWARN, maxLines = 4)

        Troubleshooting(body) { u -> link(u) }
        Spacer(Modifier.height(80.dp))
    }
}

/** Two or more buttons: side by side on TV, stacked on a narrow phone. */
@Composable
private fun Buttons(items: List<Pair<String, () -> Unit>>) {
    if (AppUi.phoneNav) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { items.forEach { (t, a) -> Row1(t, a) } }
    else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items.forEach { (t, a) -> Row1(t, a, Modifier.weight(1f)) } }
}

/** Error with Google's own words, what to do, and the page that fixes it. The text can be selected and copied. */
@Composable
private fun ErrorBox(msg: String, hint: String, link: String, body: Int, open: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Pal.WarnBox, RoundedCornerShape(8.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(msg, color = AWARN, fontSize = body.sp, lineHeight = (body + 5).sp)
                if (hint.isNotEmpty()) Text("What to do: $hint", color = Pal.Text, fontSize = body.sp, lineHeight = (body + 5).sp)
                if (link.isNotEmpty()) Text(link, color = Pal.GoldText, fontSize = (body - 1).sp)
            }
        }
        if (link.isNotEmpty()) Row1("Open this page ↗", { open(link) })
    }
}

/** The Google Cloud checklist, always visible at the bottom of the screen. */
@Composable
private fun Troubleshooting(body: Int, open: (String) -> Unit) {
    val steps = listOf(
        "1. Google Drive API is enabled in the same project (APIs & Services > Library > Google Drive API > Enable)." to GoogleErrors.DRIVE_API_URL,
        "2. The client type is \"TVs and Limited Input devices\" (not Web, Android or Desktop), and the Client ID and secret are copied from that client." to GoogleErrors.CLIENTS_URL,
        "3. Google Auth Platform > Audience: user type External, publishing status Testing, and your Gmail added under Test users." to GoogleErrors.CONSENT_URL,
        "4. Google Auth Platform > Data access: add the scopes openid, email, profile and https://www.googleapis.com/auth/drive.appdata (add drive.file too; it is used if Google refuses drive.appdata)." to GoogleErrors.DATA_ACCESS_URL,
        "5. On the Google page after entering the code: pick your account, press Continue on \"Google hasn't verified this app\", then TICK the box \"See, create, and delete its own configuration data in your Google Drive\" (or \"…files that you use with this app\") and press Continue." to "",
        "6. Still stuck? Remove TVstreamer's access at myaccount.google.com/connections and sign in again. In Testing mode Google signs you out every 7 days; Publish the app to stop that." to GoogleErrors.PERMISSIONS_URL,
    )
    Column(Modifier.fillMaxWidth().background(Pal.Field, RoundedCornerShape(8.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Troubleshooting checklist", body + 3, Pal.GoldText, maxLines = 2)
        steps.forEach { (t, u) ->
            Text(t, color = Pal.Text, fontSize = body.sp, lineHeight = (body + 5).sp)
            if (u.isNotEmpty()) Row1("Open ↗  ${u.removePrefix("https://")}", { open(u) })
        }
    }
}

@Composable
private fun SignInCode(c: Google.DeviceCode, tv: Boolean, openLink: () -> Unit) {
    val qr = remember(c.url) { qrBitmap(c.url) }
    val text: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("On your phone or computer go to", 16, ADIM)
            Label(c.url.removePrefix("https://"), if (tv) 26 else 22, Pal.Text, maxLines = 2)
            Label("and enter this code:", 16, ADIM)
            Text(c.userCode, color = Pal.GoldText, fontSize = if (tv) 40.sp else 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Label("Choose your Google account, press Continue, and tick the Google Drive box before pressing Continue again. " +
                "This page updates by itself.", if (tv) 16 else 14, ADIM, maxLines = 6)
            Row1("Open the page on this device ↗", openLink)
        }
    }
    val qrBox: @Composable () -> Unit = {
        if (qr != null) Box(Modifier.background(Color.White, RoundedCornerShape(6.dp)).padding(8.dp)) {
            Image(qr, "QR code for ${c.url}", Modifier.size(170.dp), filterQuality = FilterQuality.None)
        }
    }
    val bg = Modifier.fillMaxWidth().background(Pal.Field, RoundedCornerShape(8.dp)).padding(18.dp)
    if (tv) Row(bg, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        text(Modifier.weight(1f)); qrBox()
    } else Column(bg, verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        text(Modifier.fillMaxWidth()); qrBox()
    }
}

/** QR code image for [text] (black on white), or null if it cannot be made. */
fun qrBitmap(text: String): ImageBitmap? = runCatching {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 0))
    val bmp = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    bmp.asImageBitmap()
}.getOrNull()
