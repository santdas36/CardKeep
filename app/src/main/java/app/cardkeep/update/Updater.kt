package app.cardkeep.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Updates from the project's GitHub releases, only when you tap "Check for updates".
 * The downloaded APK is checked to be this app and newer before it's handed to Android's installer,
 * which also refuses anything not signed with the same key as the installed app.
 */
object Updater {
    private const val REPO = "santdas36/CardKeep"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val publishedAt: String)

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** Debug builds have their own package name, so a release APK would install next to them, not over them. */
    fun canSelfUpdate(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0

    /** The latest published release. Throws on network or format errors. */
    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Cards-Android")
        }
        try {
            if (c.responseCode != 200) error("GitHub returned ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: error("This release has no APK")
            Release(
                version = o.optString("tag_name").removePrefix("v").trim(),
                notes = o.optString("body").trim(),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
                publishedAt = o.optString("published_at").take(10),
            )
        } finally {
            c.disconnect()
        }
    }

    /** True when [remote] ("1.2.0") is a later version than [local] ("1.1.3"). */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun dir(context: Context) = File(context.cacheDir, "updates")

    /** Removes downloaded APKs (after an update, or a download that wasn't installed). */
    fun cleanUp(context: Context) {
        dir(context).deleteRecursively()
    }

    /** Downloads the release APK into the app's cache. [onProgress] gets 0..1. */
    suspend fun download(context: Context, r: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        cleanUp(context)
        val file = File(dir(context).apply { mkdirs() }, "Cards-${r.version}.apk")
        val c = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Cards-Android")
        }
        try {
            if (c.responseCode != 200) error("Download failed (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            var done = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (r.apkSize > 0 && file.length() != r.apkSize) error("Download was incomplete")
            file
        } catch (e: Throwable) {
            file.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Checks [apk] is this app and newer than what's installed. Returns a reason when it isn't. */
    fun verify(context: Context, apk: File): String? {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return "The download isn't a valid app"
        if (info.packageName != context.packageName) return "The download is a different app"
        @Suppress("DEPRECATION")
        val mine = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val theirs = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        @Suppress("DEPRECATION")
        val installed = if (Build.VERSION.SDK_INT >= 28) mine.longVersionCode else mine.versionCode.toLong()
        if (theirs <= installed) return "The download isn't newer than this version"
        return null
    }

    /** Whether Android lets Cards install apps ("Install unknown apps" for Cards). */
    fun mayInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Hands [apk] to Android's installer. Android shows its own confirmation unless it can update silently. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("cards.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }
}

/** Installer status: shows Android's confirmation when it needs one, and reports failures. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // cancelled on the confirmation screen
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(context, "Update didn't install" + (msg?.let { ": $it" } ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
}
