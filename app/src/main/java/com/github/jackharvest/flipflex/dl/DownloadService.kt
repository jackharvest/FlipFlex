package com.github.jackharvest.flipflex.dl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.util.Log
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.plex.PlexClient
import com.github.jackharvest.flipflex.plex.PlexPlayback
import com.github.jackharvest.flipflex.plex.Quality
import com.github.jackharvest.flipflex.store.Store
import com.github.jackharvest.flipflex.ui.SplashActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * The thing that actually fetches a download.
 *
 * ## Why a service, and why a foreground one
 *
 * A download of a 40-minute episode takes minutes. The user will close the lid,
 * which backgrounds the app, and on API 30 a backgrounded process has a few
 * minutes at most before the platform is entitled to stop it. A foreground
 * service with a notification is the only arrangement that survives that, and
 * the notification is not a formality -- it is the only place a user who has
 * left the app can see that something is still using their radio.
 *
 * ## One at a time, on purpose
 *
 * Not because the code could not run several, but because each one is a live
 * transcode on the server. Three concurrent transcodes on a home NAS is how you
 * get the bare HTTP 400 that Phase 2 spent a day on, and the phone is on one
 * radio anyway, so parallel downloads would finish at the same wall-clock time
 * while making everything else on the server worse.
 *
 * ## Not resumable
 *
 * `start.mkv` serves one continuous stream and honours no byte ranges, so an
 * interrupted transfer can only be thrown away. Everything here is arranged
 * around that: the file is written to `.part`, and the `.part` is deleted
 * rather than kept whenever the transfer does not run to completion.
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "FlipFlex/dlsvc"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 1

        /**
         * Anything smaller than this is a transcode that died at the starting
         * line, not a very short episode.
         *
         * This check exists because the transport cannot tell us. Plex closes
         * the stream cleanly when its transcoder falls over, so the connection
         * reports success and what lands is a valid, tiny, unplayable file.
         * Size is the only honest test available.
         */
        private const val MIN_PLAUSIBLE_BYTES = 256L * 1024L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var worker: Job? = null

    /**
     * Set while a transfer is running, so a cancel can interrupt the read loop
     * rather than waiting for a forty-minute download to end by itself.
     */
    @Volatile
    private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground has to happen within a few seconds of
        // startForegroundService or the platform kills the process with a
        // ForegroundServiceDidNotStartInTimeException -- which looks exactly
        // like the app crashing when a download is queued.
        startForeground(NOTIFICATION_ID, notification("Downloading", "Starting…", 0, 0))

        if (worker?.isActive == true) return START_NOT_STICKY
        worker = scope.launch { drain() }
        // NOT_STICKY: if the platform kills us the queue is still on disk, and
        // Downloads.recover puts the interrupted row back to `queued` next time
        // the app opens. Restarting automatically would resume a download on
        // mobile data with nobody watching.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        worker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---- the queue ---------------------------------------------------------

    private suspend fun drain() {
        val store = Store(this)
        while (true) {
            val entry = Downloads.nextQueued(this) ?: break

            if (store.downloadWifiOnly && !onUnmeteredNetwork()) {
                // Left queued rather than failed. The user has not done anything
                // wrong and the download should simply happen when they are next
                // on Wi-Fi; marking it failed would make them queue it twice.
                Log.i(TAG, "not on Wi-Fi, leaving ${entry.title} queued")
                break
            }

            val uri = store.serverUri
            val token = store.serverToken
            if (uri == null || token == null) break

            fetch(entry, uri, token, store)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fetch(entry: Downloads.Entry, uri: String, token: String, store: Store) {
        Downloads.setState(this, entry.ratingKey, Downloads.DOWNLOADING)
        cancelled = false

        val session = PlexPlayback.newSession()
        // The entry's own settings, not the current ones. A queue is drained
        // minutes or hours after it is filled, and reading the live preference
        // here meant an episode queued at High and downloaded after the user
        // dropped the setting to Low arrived as Low -- while its own row, the
        // details page and the badge all went on saying High. The row is the
        // record of what was asked for; this is where it gets honoured.
        val quality = Quality.byBitrate(entry.bitrateKbps, entry.resolution)
        val url = PlexPlayback.downloadUrl(
            uri, token, entry.ratingKey, session,
            quality = quality,
            subtitles = entry.subtitlesBurned,
            subtitleSize = store.subtitleSize,
        )

        val part = Downloads.partFor(this, entry)
        part.delete()

        var written = 0L
        var conn: HttpURLConnection? = null
        val ok = try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                // Generous, and deliberately so: this is a read timeout on a
                // stream the server produces in real time, so a gap of several
                // seconds while ffmpeg works is normal rather than a stall.
                readTimeout = 60_000
                instanceFollowRedirects = true
                // PLATFORM_FILE, matching what downloadUrl put in the query
                // string. A request whose header and query disagree about what
                // client it is describes itself two ways to one server.
                PlexClient.headers(token, PlexClient.PLATFORM_FILE)
                    .forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "download of ${entry.title} -> HTTP ${conn.responseCode}")
                false
            } else {
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var lastNotified = 0L
                        while (true) {
                            // Cancellation is checked per buffer rather than per
                            // file. The UI cancels by deleting the row, so that
                            // is what is tested -- one predicate covers both a
                            // cancel and a "delete while downloading".
                            if (cancelled || Downloads.get(this, entry.ratingKey) == null) {
                                return@use
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (written - lastNotified > 2_000_000L) {
                                lastNotified = written
                                notifyProgress(entry, written)
                            }
                        }
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "download of ${entry.title} failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }

        // Always, on every path, and both calls matter for different reasons.
        //
        // stop() releases the ffmpeg process on the server. The `stopped`
        // timeline is the one that clears Plex's belief that this *item* has a
        // live session -- which is item-scoped, not client-scoped, so a
        // download that failed halfway would otherwise block its own retry and
        // block playing the same episode, with a bare 400 either way. Stopping
        // the transcode is not sufficient on its own; this was measured in
        // Phase 2 and it is what made the first download probe here disagree
        // with itself.
        //
        // The position is the item's own, never zero: `stopped` with time=0
        // wipes the resume point, which would turn "the download failed" into
        // "and it forgot where you were".
        scope.launch {
            PlexPlayback.stop(uri, token, session)
            PlexPlayback.timeline(
                uri, token, entry.ratingKey, "stopped",
                entry.viewOffsetMs, entry.durationMs,
            )
        }

        val row = Downloads.get(this, entry.ratingKey)
        if (row == null) {
            // Cancelled by the user deleting the row. remove() already took the
            // files; nothing to record.
            part.delete()
            return
        }
        if (cancelled) {
            part.delete()
            Downloads.setState(this, entry.ratingKey, Downloads.QUEUED)
            return
        }

        if (ok && written >= MIN_PLAUSIBLE_BYTES) {
            val target = Downloads.fileFor(this, entry)
            // Splice in the seek index before the file becomes visible as a
            // download. What the transcoder produces has no Cues element and is
            // therefore completely unseekable -- see MatroskaIndex. Doing it
            // here rather than at play time means the cost is paid once, while
            // the user is already waiting, instead of on every open.
            //
            // Indexing writes a second copy and then drops the first, so it
            // needs the file's size again in free space for a few seconds. A
            // failure is not a failed download: the unindexed file is perfectly
            // playable and merely cannot be skipped through, so it is kept.
            notifyIndexing(entry)
            val indexed = MatroskaIndex.addCues(part, target)
            if (indexed) {
                part.delete()
            } else {
                part.renameTo(target)
            }
            Downloads.setState(
                this, entry.ratingKey, Downloads.DONE, target.length(), seekable = indexed,
            )
            Log.i(
                TAG,
                "finished ${entry.title} (${Downloads.humanBytes(target.length())})" +
                    if (indexed) ", seekable" else ", NOT seekable",
            )
        } else {
            part.delete()
            Downloads.setState(this, entry.ratingKey, Downloads.FAILED, 0)
            Log.w(TAG, "FAILED ${entry.title} after ${Downloads.humanBytes(written)}")
        }
    }

    /**
     * Wi-Fi, or anything else the system considers unmetered.
     *
     * NOT_METERED rather than TRANSPORT_WIFI, because a metered hotspot is a
     * phone plan wearing a Wi-Fi hat and the whole point of the setting is not
     * to spend somebody's data allowance on a film.
     */
    private fun onUnmeteredNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // ---- notification ------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_LOW: no sound, no heads-up. A download starting must never
        // interrupt what is already playing on this handset's one speaker.
        val ch = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    /**
     * Indexing a 50 MB episode takes a few seconds of solid disk on an MT6739,
     * and it happens after the transfer has visibly finished. Without a line
     * saying so, the notification sits at 99% doing nothing anybody can see.
     */
    private fun notifyIndexing(entry: Downloads.Entry) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(entry.title, "Building the seek index…", 0, 0),
        )
    }

    private fun notifyProgress(entry: Downloads.Entry, written: Long) {
        val pct =
            if (entry.estBytes > 0) ((written * 100) / entry.estBytes).toInt().coerceIn(0, 99)
            else 0
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            NOTIFICATION_ID,
            notification(
                entry.title,
                "${Downloads.humanBytes(written)} · ${Downloads.countDone(this)} done",
                pct,
                if (entry.estBytes > 0) 100 else 0,
            ),
        )
    }

    private fun notification(title: String, text: String, progress: Int, max: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SplashActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(open)
            .setOngoing(true)
            .apply { if (max > 0) setProgress(max, progress, false) }
            .build()
    }
}
