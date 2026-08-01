package com.github.jackharvest.flipflex.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.github.jackharvest.flipflex.plex.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Is there a newer FlipFlex, and can this phone install it by itself?"
 *
 * ## Why this exists at all
 *
 * Every other way onto this handset needs a Mac: adb, a cable, and a build
 * environment. That is fine for the person who wrote it and useless for the
 * person who has one and reads that a bug they reported is fixed. There is no
 * store on this phone -- TCL's launcher will not even *list* an app it does not
 * know about, see docs/launcher-menu.md -- so the release page on GitHub is the
 * only distribution channel there is, and this is the only way to reach it
 * without a computer.
 *
 * ## What the platform demands, measured rather than assumed
 *
 * - the installer takes **`content:` URIs only**. A `file:` intent does not
 *   resolve at all on API 30: `Activity not started, unable to resolve Intent`,
 *   with the installer sitting right there enabled. So this uses
 *   [PackageInstaller] and streams the APK into a session, which needs no
 *   FileProvider and never writes a copy anywhere the rest of the system can
 *   see;
 * - the caller must **declare** `REQUEST_INSTALL_PACKAGES` in its manifest.
 *   Granting the appop is not enough and the failure is only in the log:
 *   `InstallStart: Requesting uid 2000 needs to declare permission
 *   android.permission.REQUEST_INSTALL_PACKAGES`. On screen the install simply
 *   does not happen;
 * - the user must then allow this app as a source, once, in system Settings.
 *   [canInstall] is that question and [sourceSettings] is the trip. It cannot
 *   be asked for in a dialog -- there is no runtime-permission prompt for it.
 *
 * The signature does the rest: an update signed with the same key installs over
 * the old one and keeps the Plex token and every downloaded episode, which is
 * the whole reason ~/.flipflex must never be lost. An APK signed with anything
 * else is refused by the platform here, which is exactly what we want -- this
 * downloads over the network and the signature check is the thing standing
 * between a release page and an arbitrary APK.
 */
object Updates {

    private const val TAG = "FlipFlex/update"

    /**
     * The releases API, unauthenticated: 60 requests an hour per address, and
     * this asks once per press of a row nobody presses twice.
     */
    private const val LATEST =
        "https://api.github.com/repos/jackharvest/FlipFlex/releases/latest"

    private const val CONNECT_MS = 10_000
    private const val READ_MS = 20_000

    /** A published release, as much of it as this app cares about. */
    data class Release(
        val version: String,
        val url: String,
        val size: Long,
        /** What changed, as bullets, ready to paint. Empty if the notes say nothing. */
        val notes: String,
    )

    sealed interface Check {
        /** [release] is newer than what is running. */
        data class Newer(val release: Release) : Check
        /** Nothing to do. Carries the running version so the caller can say it. */
        data class Current(val version: String) : Check
        /** GitHub could not be reached, or answered something unusable. */
        data object Failed : Check
    }

    /**
     * Ask GitHub what the latest release is and compare it with [running].
     *
     * A release with no APK attached is [Check.Failed] rather than [Check.Newer]
     * with an empty URL: the tag exists as soon as `tools/release.sh publish`
     * pushes it, and the asset finishes uploading a moment later, so there is a
     * real window where the newest release cannot actually be downloaded.
     */
    suspend fun check(running: String): Check = withContext(Dispatchers.IO) {
        val body = get(LATEST) ?: return@withContext Check.Failed
        val release = runCatching { parse(body) }.getOrNull() ?: return@withContext Check.Failed
        if (isNewer(release.version, running)) Check.Newer(release) else Check.Current(running)
    }

    /**
     * `str` rather than `optString`, for the reason in plex/Json.kt: a key that
     * is present and explicitly null yields the four-character string "null".
     * That is a Plex habit and GitHub does not share it, but the rule is cheap
     * to keep and the failure it prevents is silent. Its HTML unescaping is a
     * no-op on a tag name and a release URL, neither of which can contain an
     * entity.
     */
    private fun parse(body: String): Release? {
        val json = JSONObject(body)
        // Tags are written `v1.0.1` and versionName is `1.0.1`; the whole
        // comparison happens on the bare numbers.
        val version = json.str("tag_name").removePrefix("v")
        val notes = bullets(json.str("body"))
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.str("name")
            if (!name.endsWith(".apk")) continue
            val url = asset.str("browser_download_url")
            val size = asset.optLong("size")
            if (version.isEmpty() || url.isEmpty() || size <= 0) return null
            return Release(version, url, size, notes)
        }
        return null
    }

    /**
     * Turn a release page's markdown into something a 240x320 panel can show.
     *
     * The notes are written for the release list on GitHub, and the phone shows
     * the same text before asking whether to download -- so this has to survive
     * whatever markdown was used, without pulling in a renderer.
     *
     * What it does: drops headings, rules and the `**Full Changelog**` line
     * GitHub appends; turns `- ` and `* ` into a real bullet, because a leading
     * hyphen at 10sp reads as a dash rather than as a list; strips `**` and
     * backticks, which are noise once nothing can be bold.
     *
     * It also **reflows**, and that is not cosmetic. The notes are written in a
     * file wrapped at eighty columns, so a single bullet arrives as three lines
     * -- which would spend three of [MAX_LINES] on one point, and drop the last
     * two bullets of a four-bullet release. A line that does not start a new
     * bullet belongs to the one above it.
     *
     * What it does not do is scroll. The panel is as tall as its contents and
     * sits at the bottom of the screen, so notes long enough to push the Cancel
     * row off the top would take the way out of the panel with them.
     * [MAX_LINES] and [MAX_CHARS] are what stop that, and the ellipsis is
     * honest: the whole text is on the release page, and this is a summary by
     * design.
     */
    internal fun bullets(body: String): String {
        val points = mutableListOf<String>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("---")) continue
            if (line.startsWith("**Full Changelog**")) continue
            val marked = line.startsWith("- ") || line.startsWith("* ")
            val text = line.removePrefix("- ").removePrefix("* ")
                .replace("**", "")
                .replace("`", "")
            when {
                marked -> points += "• $text"
                // A continuation of the point above, from a file wrapped at
                // eighty columns. An unmarked line with nothing above it is a
                // paragraph of its own -- the 1.0.0 notes open with one.
                points.isNotEmpty() -> points[points.lastIndex] += " $text"
                else -> points += text
            }
            if (points.size > MAX_LINES) break
        }
        val joined = points.take(MAX_LINES).joinToString("\n")
        return if (joined.length <= MAX_CHARS) joined else joined.take(MAX_CHARS).trimEnd() + "…"
    }

    /**
     * Dotted-number comparison, and nothing cleverer.
     *
     * `versionName` is set by hand in app/build.gradle.kts and has been three
     * numbers since the first release, so this is the whole problem. A part that
     * is not a number makes the whole comparison false rather than throwing:
     * offering an update the user cannot evaluate is worse than offering none,
     * and the honest reading of a version this code does not understand is that
     * it is not obviously newer.
     */
    internal fun isNewer(candidate: String, running: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: return false }
        val b = running.split('.').map { it.toIntOrNull() ?: return false }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ---- installing --------------------------------------------------------

    /** True when the user has already allowed this app to install packages. */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /**
     * The system screen carrying the one toggle that makes [canInstall] true.
     *
     * `package:` in the data URI is what makes it open on *this* app rather than
     * on the list of every app on the phone -- which on a 240x320 panel is a
     * scroll through everything TCL ships before reaching the one entry that
     * matters. The toggle is greyed out for an app that does not declare
     * REQUEST_INSTALL_PACKAGES, which is how this was diagnosed in the first
     * place: it renders, it just cannot be switched on.
     */
    fun sourceSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Fetch [release] straight into an install session and commit it.
     *
     * Nothing is written to storage. The bytes go from the socket into the
     * session, which is the platform's own staging area -- so a transfer that
     * dies halfway leaves an abandoned session for the system to clean up
     * rather than a half APK in the app's files, and there is no second copy of
     * twelve megabytes on a phone whose whole purpose is holding episodes.
     *
     * [onProgress] is called with 0..1 on the IO thread; the caller marshals.
     * Returns null on success, or a line to show the user on failure -- at which
     * point nothing has been installed, because the session is only committed
     * after the last byte.
     *
     * The user still has to confirm: committing raises
     * `STATUS_PENDING_USER_ACTION`, which [InstallReceiver] turns into the
     * system's own "update this app?" screen. There is no way to skip that
     * without being a system app, and there should not be.
     */
    suspend fun download(
        ctx: Context,
        release: Release,
        onProgress: (Float) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val installer = ctx.packageManager.packageInstaller
        var sessionId = -1
        var conn: HttpURLConnection? = null
        try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                // Naming the package means the platform refuses a session that
                // turns out to carry something else, before any of it is
                // written. Belt and braces with the signature check.
                setAppPackageName(ctx.packageName)
                setSize(release.size)
            }
            sessionId = installer.createSession(params)

            conn = (URL(release.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                // The download URL is a redirect to objects.githubusercontent.com.
                // Same scheme, so HttpURLConnection follows it by itself.
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "GET ${release.url} -> HTTP ${conn.responseCode}")
                installer.abandonSession(sessionId)
                return@withContext "GitHub answered HTTP ${conn.responseCode}."
            }

            installer.openSession(sessionId).use { session ->
                session.openWrite(NAME, 0, release.size).use { out ->
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            total += read
                            onProgress((total.toFloat() / release.size).coerceIn(0f, 1f))
                        }
                        // A truncated transfer that the socket never reported as
                        // an error would otherwise be committed as a short APK,
                        // and the platform's complaint about it names nothing
                        // useful. This one names the real problem.
                        if (total != release.size) {
                            throw IOException("got $total of ${release.size} bytes")
                        }
                    }
                    session.fsync(out)
                }
                session.commit(confirmIntent(ctx, sessionId).intentSender)
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "update failed: ${e.javaClass.simpleName}: ${e.message}")
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            "Download failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Where the session reports back to.
     *
     * A manifest receiver rather than one registered by the activity, because
     * the last thing that happens on success is this process being killed and
     * replaced -- there is no instance left to deliver anything to.
     *
     * FLAG_MUTABLE is not passed and must not be: it arrived in API 31 and a
     * PendingIntent is mutable by default below that. The system fills the
     * status extras in itself, so an immutable one would arrive empty.
     */
    private fun confirmIntent(ctx: Context, sessionId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            sessionId,
            Intent(ctx, InstallReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                instanceFollowRedirects = true
                // GitHub serves the v3 shape without this and may not for ever.
                setRequestProperty("Accept", "application/vnd.github+json")
                // The API answers 403 to a request with no User-Agent.
                setRequestProperty("User-Agent", "FlipFlex")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "GET $url -> HTTP ${conn.responseCode}")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: IOException) {
            Log.w(TAG, "GET $url failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** The name of the one file in the session. Never seen by anyone. */
    private const val NAME = "flipflex.apk"

    /**
     * How much of the release notes the confirm panel can carry.
     *
     * Six lines and 280 characters, measured against the panel rather than
     * guessed: the title, the notes, the facts line and two option rows have to
     * fit in 320dp with the status bar and the softkey bar already spent, and a
     * bullet that wraps counts twice. Notes written to the house style -- a few
     * bullets somebody would actually notice, everything else swept into "and
     * other bug fixes" -- come in well under this. It is a backstop, not a
     * budget to fill.
     */
    private const val MAX_LINES = 6
    private const val MAX_CHARS = 280
}
