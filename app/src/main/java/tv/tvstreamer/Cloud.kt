package tv.tvstreamer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Google account + sync of favourites, hidden items, languages, sorting, remote keys, Continue watching, Fix match
 * choices and UI settings through a JSON file in the user's private Google Drive app folder.
 *
 * Changes are recorded locally first (a journal with change times), so nothing is lost offline; the next sync
 * merges entry by entry with the cloud copy (newer change wins, removals kept as tombstones).
 */
object Cloud {
    private const val DEBOUNCE_MS = 10_000L

    var email by mutableStateOf("")
        private set
    /** Google profile name (for the Profile screen). */
    var name by mutableStateOf("")
        private set
    /** Google profile photo URL. */
    var photo by mutableStateOf("")
        private set
    var status by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set
    /** What to do about [error], and a page that helps (may be empty). */
    var errorHint by mutableStateOf("")
        private set
    var errorLink by mutableStateOf("")
        private set
    var lastSync by mutableLongStateOf(0L)
        private set
    var busy by mutableStateOf(false)
        private set

    private lateinit var acct: SharedPreferences
    private lateinit var journalFile: File
    private var repo: Repo? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pending: Job? = null
    private var device = android.os.Build.MODEL ?: "device"
    @Volatile private var accessToken = ""
    @Volatile private var accessExp = 0L

    val signedIn: Boolean get() = ::acct.isInitialized && refreshToken.isNotEmpty()

    var clientId: String
        get() = acct.getString("client_id", "") ?: ""
        set(v) { acct.edit().putString("client_id", v.trim()).apply() }
    var clientSecret: String
        get() = acct.getString("client_secret", "") ?: ""
        set(v) { acct.edit().putString("client_secret", v.trim()).apply() }
    private var refreshToken: String
        get() = acct.getString("refresh", "") ?: ""
        set(v) { acct.edit().putString("refresh", v).commit() }
    /** Also sync portal URL / MAC / serial / password and TMDB/OMDb keys (on by default; the Google client is never synced). */
    var syncSecrets: Boolean
        get() = acct.getBoolean("sync_secrets", true)
        private set(v) { acct.edit().putBoolean("sync_secrets", v).commit() }
    /** Where the sync file lives for the signed-in account (app-data folder, or a file in My Drive when Google refused drive.appdata). */
    val space: DriveSpace get() = DriveSpace.of(acct.getString("drive_space", null))
    /** False when the account prefs could not be encrypted on this device (they are still app-private). */
    var encrypted = true
        private set

    fun init(ctx: Context, r: Repo) {
        if (::acct.isInitialized) { repo = r; hook(r); return }
        repo = r
        acct = try {
            val key = androidx.security.crypto.MasterKey.Builder(ctx).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                ctx, "account", key,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            encrypted = false
            ctx.getSharedPreferences("account_plain", Context.MODE_PRIVATE)
        }
        journalFile = File(ctx.filesDir, "sync_journal.json")
        email = acct.getString("email", "") ?: ""
        name = acct.getString("name", "") ?: ""
        photo = acct.getString("photo", "") ?: ""
        lastSync = acct.getLong("last_sync", 0L)
        status = if (signedIn) "Signed in" else ""
        hook(r)
    }

    private fun hook(r: Repo) {
        r.store.onChange = { changed() }
        Tmdb.onChange = { changed() }
    }

    /** Something synced changed: sync about 10 s after the last change. */
    fun changed() {
        if (!signedIn) return
        pending?.cancel()
        pending = scope.launch { delay(DEBOUNCE_MS); runSync() }
    }

    /** App start / back in front: sync soon. */
    fun syncSoon(delayMs: Long = 1500) {
        if (!signedIn) return
        pending?.cancel()
        pending = scope.launch { delay(delayMs); runSync() }
    }

    /** App going to the background: record changes now and try to upload. */
    fun flush() {
        if (!signedIn) return
        pending?.cancel()
        pending = scope.launch { runSync() }
    }

    fun syncNow() { pending?.cancel(); pending = scope.launch { runSync() } }

    private suspend fun runSync() {
        try { sync() } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
    }

    private fun fail(e: Exception) {
        val g = e as? GoogleError
        error = g?.message ?: "Sync failed: ${e.javaClass.simpleName}"
        errorHint = g?.hint ?: ""; errorLink = g?.link ?: ""
        status = if (g?.code == "offline") "Offline: changes are saved and will sync later" else "Sync problem"
        if (g?.authLost == true) { clearTokens(); status = "Signed out" }
    }

    // ---------- Sign-in ----------

    fun startSignIn(): Google.DeviceCode {
        if (clientId.isEmpty() || clientSecret.isEmpty()) throw GoogleError("Enter the Client ID and Client secret first.", "no_client")
        return try { Google.startDevice(clientId, DriveSpace.APPDATA) } catch (e: GoogleError) {
            // Google's device flow rejects drive.appdata for some clients (a long-standing Google issue);
            // drive.file still lets the app keep its own file in the user's Drive.
            if (e.code != "invalid_scope") throw e
            try { Google.startDevice(clientId, DriveSpace.FILE) } catch (e2: GoogleError) { throw if (e2.code == "invalid_scope") e else e2 }
        }
    }

    /** Polls until the user approves (true), or throws. Call from a coroutine that is cancelled when the screen closes. */
    suspend fun waitForSignIn(dc: Google.DeviceCode): Boolean = withContext(Dispatchers.IO) {
        var interval = dc.interval * 1000L
        while (isActive) {
            if (System.currentTimeMillis() > dc.expiresAt) throw GoogleError("The code expired. Start sign-in again.", "expired_token")
            delay(interval)
            val t = try { Google.pollDevice(clientId, clientSecret, dc) } catch (e: GoogleError) {
                if (e.code == "slow_down") { interval += 5000; null }
                else if (e.code == "offline") null
                else throw e
            } ?: continue
            if (!GoogleErrors.hasScope(t.scope, dc.space.scope)) {
                // Granular consent: the Drive box was left unticked. Drop this grant and ask again.
                Google.revoke(t.refresh ?: t.access)
                throw GoogleErrors.scopeMissing(dc.space)
            }
            accessToken = t.access; accessExp = t.expiresAt
            acct.edit().putString("drive_space", dc.space.name).commit()
            refreshToken = t.refresh ?: throw GoogleError("Google did not return a refresh token. Remove the app's access in your Google account and try again.")
            val mail = t.email ?: Google.userEmail(t.access) ?: ""
            acct.edit().putString("email", mail).putString("name", t.name ?: "").putString("photo", t.photo ?: "").apply()
            email = mail; name = t.name ?: ""; photo = t.photo ?: ""; error = ""; errorHint = ""; errorLink = ""; status = "Signed in"
            // First sync on this device: cloud copy wins conflicts, this device's extra items are added
            runSync()
            return@withContext true
        }
        false
    }

    private fun clearTokens() {
        accessToken = ""; accessExp = 0
        acct.edit().remove("refresh").remove("drive_space").remove("email").remove("name").remove("photo").remove("last_sync").remove("file_id").commit()
        email = ""; name = ""; photo = ""; lastSync = 0
        runCatching { journalFile.delete() }   // next sign-in is a "first sync" again
    }

    fun signOut() {
        pending?.cancel()
        val r = refreshToken
        clearTokens(); error = ""; errorHint = ""; errorLink = ""; status = "Signed out"
        if (r.isNotEmpty()) scope.launch { Google.revoke(r) }
    }

    /** Deletes the sync file from Drive, then signs this device out (so it is not uploaded again at once). */
    suspend fun deleteCloud(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val t = token()
                val ids = Google.findFiles(t, space)
                ids.forEach { Google.delete(t, it, space) }
                signOut()
                "Deleted the cloud copy (${ids.size} file${if (ids.size == 1) "" else "s"}) and signed out. Data on this device is unchanged."
            } catch (e: Exception) { (e as? GoogleError)?.message ?: "Delete failed: ${e.javaClass.simpleName}" }
        }
    }

    /** Turning portal-login sync off removes those values from the cloud copy at the next sync. */
    fun changeSyncSecrets(on: Boolean) {
        if (on == syncSecrets) return
        syncSecrets = on
        if (!on) {
            val j = loadJournal()
            val now = System.currentTimeMillis()
            saveJournal(j.mapValues { (k, e) -> if (Sync.isSecret(k) && e.v != null) SyncEntry(maxOf(now, e.t + 1), null) else e })
        }
        syncNow()
    }

    private fun token(): String {
        if (accessToken.isNotEmpty() && System.currentTimeMillis() < accessExp - 60_000) return accessToken
        val r = refreshToken
        if (r.isEmpty()) throw GoogleError("Not signed in")
        val t = Google.refresh(clientId, clientSecret, r)
        accessToken = t.access; accessExp = t.expiresAt
        if (!t.refresh.isNullOrEmpty() && t.refresh != r) refreshToken = t.refresh
        return accessToken
    }

    private inline fun <T> authed(block: (String) -> T): T = try { block(token()) } catch (e: GoogleError) {
        if (e.code != "401") throw e
        accessToken = ""; block(token())
    }

    // ---------- Journal ----------

    private fun loadJournal(): Map<String, SyncEntry> =
        runCatching { if (journalFile.exists()) SyncDoc.fromJson(journalFile.readText()).entries else emptyMap() }.getOrDefault(emptyMap())

    private fun saveJournal(m: Map<String, SyncEntry>) {
        runCatching {
            val tmp = File(journalFile.parentFile, journalFile.name + ".tmp")
            tmp.writeText(SyncDoc(updated = System.currentTimeMillis(), device = device, entries = m).toJson().toString())
            tmp.renameTo(journalFile)
        }
    }

    private fun readLocal(r: Repo): SyncLocal = r.store.syncRead(syncSecrets).copy(fixes = Tmdb.overridesSnapshot())

    // ---------- Sync ----------

    private suspend fun sync() = lock.withLock {
        val r = repo ?: return
        if (!signedIn) return
        busy = true; status = "Syncing..."
        try {
            val withSecrets = syncSecrets
            val skip: (String) -> Boolean = { !withSecrets && Sync.isSecret(it) }
            val now = System.currentTimeMillis()
            val first = !journalFile.exists()
            // 1. Record local changes (works offline)
            val local = readLocal(r)
            val journal = Sync.stamp(loadJournal(), SyncCodec.flatten(local), now, first, skip)
            saveJournal(journal)

            // 2. Fetch the cloud copy
            val sp = space
            val ids = authed { Google.findFiles(it, sp) }
            val fileId = ids.firstOrNull()
            val remoteText = fileId?.let { id -> authed { Google.download(it, id, sp) } }
            val remote = remoteText?.let { SyncDoc.fromJson(it) }
            if (remote != null && remote.schema > Sync.SCHEMA)
                throw GoogleError("The cloud copy was written by a newer TVstreamer. Update this app to sync.")

            // 3. Merge and upload if the cloud copy is missing anything
            val merged = Sync.prune(Sync.merge(journal, remote?.entries ?: emptyMap()), now)
            if (remote == null || merged != remote.entries) {
                val doc = SyncDoc(updated = now, device = device, entries = merged)
                val id = authed { Google.upload(it, fileId, doc.toJson().toString(), sp) }
                acct.edit().putString("file_id", id).apply()
            }
            // Extra copies (two devices created the file at the same moment): merged into ours above, remove them
            ids.drop(1).forEach { id -> runCatching { authed { Google.delete(it, id, sp) } } }

            // 4. Apply what changed elsewhere
            val wanted = Sync.values(merged).filterKeys { !skip(it) }
            val have = SyncCodec.flatten(local).filterKeys { !skip(it) }
            var applied = merged
            if (wanted != have) {
                val target = SyncCodec.unflatten(merged.filterKeys { !skip(it) })
                apply(r, target, withSecrets)
                // Keep the journal equal to what is stored now, with the merged change times
                val after = SyncCodec.flatten(readLocal(r))
                applied = merged.mapValues { (k, e) -> val v = after[k]; if (e.v != null && v != null && v != e.v) SyncEntry(e.t, v) else e }
            }
            saveJournal(applied)
            lastSync = System.currentTimeMillis()
            acct.edit().putLong("last_sync", lastSync).apply()
            error = ""; errorHint = ""; errorLink = ""; status = "Synced"
            refreshProfile()
        } finally { busy = false }
    }

    /** Name / email / photo from Google, at most every 6 hours. */
    private fun refreshProfile() {
        if (System.currentTimeMillis() - acct.getLong("profile_at", 0) < 6 * 3600_000L) return
        val o = runCatching { authed { Google.userInfo(it) } }.getOrNull() ?: return
        val e = o.optString("email").ifEmpty { email }; val n = o.optString("name").ifEmpty { name }; val p = o.optString("picture").ifEmpty { photo }
        acct.edit().putString("email", e).putString("name", n).putString("photo", p).putLong("profile_at", System.currentTimeMillis()).apply()
        email = e; name = n; photo = p
    }

    private suspend fun apply(r: Repo, l: SyncLocal, withSecrets: Boolean) {
        val loginChanged = r.store.syncWrite(l, withSecrets)
        Tmdb.replaceOverrides(l.fixes)
        if (withSecrets) { Tmdb.setKeys(r.store.tmdbKey, r.store.omdbKey); Gemini.useKey(r.store.geminiKey) }
        withContext(Dispatchers.Main) {
            r.reloadSynced()
            Keys.reload(r.store)
        }
        if (loginChanged) { r.api.reset(); r.load() }
    }
}
