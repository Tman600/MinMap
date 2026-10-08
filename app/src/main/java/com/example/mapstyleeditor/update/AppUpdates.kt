package com.example.mapstyleeditor.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A MinMap release on GitHub. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkBytes: Long,
    /** The APK's published SHA-256, from GitHub's own digest or the release's SHA256SUMS. */
    val sha256: String?,
)

/** What the Updates panel shows. */
data class UpdateState(
    val checking: Boolean = false,
    val checkedAt: Long = 0,
    /** The newest release, once checked; newer than this app only if [available]. */
    val latest: Release? = null,
    val available: Boolean = false,
    /** 0..1 while downloading. */
    val progress: Float? = null,
    /** Waiting on Android's installer (or its confirmation screen). */
    val installing: Boolean = false,
    val error: String? = null,
)

/**
 * Keeps MinMap up to date from its GitHub releases (github.com/Tman600/MinMap): checks for a newer
 * version, downloads the APK, makes sure it's the real thing (its SHA-256 matches the published one
 * and it's signed with the same key as this app), and hands it to Android's package installer.
 *
 * Android asks the user to confirm the first update. MinMap is then the app that installed itself,
 * so on Android 12 and later the following updates go through without asking.
 */
object AppUpdates {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Where releases are listed: GitHub's API for the repository (a debug build can point elsewhere). */
    var feedUrl = "https://api.github.com/repos/Tman600/MinMap/releases/latest"

    fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    fun autoCheck(context: Context) = prefs(context).getBoolean(KEY_AUTO_CHECK, true)
    fun autoInstall(context: Context) = prefs(context).getBoolean(KEY_AUTO_INSTALL, true)
    fun setAutoCheck(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_AUTO_CHECK, on) }
    fun setAutoInstall(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_AUTO_INSTALL, on) }

    fun currentVersion(context: Context): String = packageInfo(context, 0).versionName.orEmpty()

    /** Whether Android lets MinMap install updates (the per-app "Install unknown apps" switch). */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" switch for MinMap. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Run when the app comes to the front: checks at most twice a day when automatic checks are on,
     * and with automatic installs on (and Wi-Fi or another unmetered connection), installs right away.
     */
    suspend fun onAppResumed(context: Context) {
        if (!autoCheck(context)) return
        if (System.currentTimeMillis() - prefs(context).getLong(KEY_CHECKED_AT, 0) < AUTO_CHECK_EVERY_MS) return
        check(context)
        val s = state.value
        if (s.available && autoInstall(context) && unmetered(context) && canInstall(context)) downloadAndInstall(context)
    }

    /** Asks GitHub for the latest release. */
    suspend fun check(context: Context) {
        _state.update { it.copy(checking = true, error = null) }
        val release = fetchLatest()
        val now = System.currentTimeMillis()
        prefs(context).edit { putLong(KEY_CHECKED_AT, now) }
        _state.update {
            if (release == null) {
                it.copy(checking = false, error = "Couldn't reach GitHub. Check your connection and try again.")
            } else {
                it.copy(checking = false, checkedAt = now, latest = release, available = newer(release.version, currentVersion(context)))
            }
        }
    }

    /** Downloads the latest release, checks it, and starts installing it. */
    suspend fun downloadAndInstall(context: Context) {
        val release = state.value.latest ?: return
        if (state.value.progress != null || state.value.installing) return
        if (!canInstall(context)) {
            _state.update { it.copy(error = "Allow MinMap to install updates first.") }
            return
        }
        _state.update { it.copy(progress = 0f, error = null) }
        val problem = withContext(Dispatchers.IO) {
            val file = runCatching { download(context, release) }.getOrElse {
                Log.w(TAG, "Update download failed", it)
                return@withContext "The download didn't finish. Check your connection and try again."
            }
            verify(context, release, file)?.let { return@withContext it }
            runCatching { install(context, file) }.getOrElse {
                Log.w(TAG, "Update install couldn't start", it)
                return@withContext "Android's installer couldn't start the update."
            }
            prefs(context).edit { putString(KEY_INSTALLING, release.version) }
            null
        }
        _state.update { it.copy(progress = null, installing = problem == null, error = problem) }
    }

    /** "MinMap updated to 1.4" once, after an update this app installed; null otherwise. */
    fun justUpdatedTo(context: Context): String? {
        val version = prefs(context).getString(KEY_INSTALLING, null) ?: return null
        val now = currentVersion(context)
        if (!newer(version, now)) prefs(context).edit { remove(KEY_INSTALLING) }
        return version.takeIf { it == now }
    }

    internal fun installFinished(message: String?) {
        _state.update { it.copy(installing = false, error = message) }
    }

    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(httpText(feedUrl) ?: return@runCatching null)
            val assets = json.getJSONArray("assets")
            val all = (0 until assets.length()).map { assets.getJSONObject(it) }
            // The phone build (arm64), not the larger universal one.
            val apk = all.filter { it.getString("name").endsWith(".apk") }
                .minByOrNull { if ("universal" in it.getString("name")) 1 else 0 } ?: return@runCatching null
            val name = apk.getString("name")
            val sha = apk.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 }
                ?: all.find { it.getString("name") == "SHA256SUMS" }?.let { sums ->
                    httpText(sums.getString("browser_download_url"))?.lineSequence()
                        ?.map { it.trim().split(Regex("\\s+")) }
                        ?.firstOrNull { it.size == 2 && it[1].trimStart('*') == name }?.get(0)
                }
            Release(
                version = json.getString("tag_name").removePrefix("v"),
                notes = json.optString("body").replace("**", "").replace("```", "").trim(),
                apkUrl = apk.getString("browser_download_url"),
                apkName = name,
                apkBytes = apk.optLong("size"),
                sha256 = sha?.lowercase(),
            )
        }.getOrNull()
    }

    /**
     * Downloads the release's APK to the app's cache. A dropped connection (common on mobile data)
     * resumes where it stopped, up to a few times, where the server allows it, or starts over.
     */
    private fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, release.apkName)
        var attempt = 0
        while (true) {
            try {
                downloadOnce(release, file)
                return file
            } catch (e: java.io.IOException) {
                if (++attempt >= DOWNLOAD_ATTEMPTS) throw e
                Log.w(TAG, "Update download interrupted at ${file.length()} bytes; resuming", e)
                Thread.sleep(2_000L * attempt)
            }
        }
    }

    private fun downloadOnce(release: Release, file: File) {
        val have = file.length()
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!resumed && conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode}")
            var done = if (resumed) have else 0L
            val total = if (resumed) release.apkBytes else conn.contentLengthLong.takeIf { it > 0 } ?: release.apkBytes
            conn.inputStream.use { input ->
                java.io.FileOutputStream(file, resumed).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) _state.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Null when [file] is the genuine [release] of this app; otherwise what's wrong with it. */
    private fun verify(context: Context, release: Release, file: File): String? {
        val expected = release.sha256 ?: return "This release has no published checksum, so it wasn't installed."
        val actual = MessageDigest.getInstance("SHA-256").let { md ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    md.update(buffer, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (actual != expected) return "The download didn't match its published checksum, so it wasn't installed."
        val archive = context.packageManager.getPackageArchiveInfo(file.path, signingFlags())
            ?: return "The download isn't a working app file."
        if (archive.packageName != context.packageName) return "The download is a different app, so it wasn't installed."
        if (signers(archive) != signers(packageInfo(context, signingFlags()))) {
            return "The download isn't signed with MinMap's key, so it wasn't installed."
        }
        return null
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Lets later updates go through without asking, once MinMap has installed itself once.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("minmap.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    }

    private fun httpText(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun unmetered(context: Context) =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false

    @Suppress("DEPRECATION")
    private fun signingFlags() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
        return certs.orEmpty().map { it.toCharsString() }.toSet()
    }

    private fun packageInfo(context: Context, flags: Int): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, flags)

    /** Whether version [a] ("1.4", "1.0.1") is later than [b]. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private const val KEY_AUTO_CHECK = "auto_check"
    private const val KEY_AUTO_INSTALL = "auto_install"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_INSTALLING = "installing_version"
    private const val AUTO_CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val USER_AGENT = "MinMap (Android app updater)"
    private const val TAG = "MinMapUpdates"
    private const val DOWNLOAD_ATTEMPTS = 4
}

/** Hears back from Android's installer: shows its confirmation screen when needed, and any failure. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdates.installFinished(null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppUpdates.installFinished("The update was cancelled.")
            else -> AppUpdates.installFinished(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "The update didn't install: $it" }
                    ?: "The update didn't install.",
            )
        }
    }
}
