package com.github.jackharvest.flipflex.dl

import android.content.Context
import android.util.Log
import com.github.jackharvest.flipflex.plex.PlexItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What is on the phone, and what is on its way there.
 *
 * ## Why there is an index at all, rather than a directory scan
 *
 * Because the interesting states have no file yet. A queued item and a failed
 * one both have to appear in the Downloads list -- a queue you cannot see is a
 * queue that looks broken the moment the Wi-Fi drops -- and neither of them has
 * anything on disk to scan. PocketFlex reached the same shape with a TSV; this
 * is the same idea in the one format the platform already parses.
 *
 * ## Why the row carries the hierarchy rather than pointing at it
 *
 * [showTitle], [seasonTitle] and [code] are copied onto every entry instead of
 * being looked up from the server when the list is drawn. That is duplication,
 * and it is the entire point: the Downloads library has to be browsable with
 * the radio off, which is the only reason any of this exists. An index that
 * needed a `/library/metadata` call to work out which season an episode belongs
 * to would be a library that only works when you do not need it.
 *
 * [code] is "S01E04". It exists because a season folder sorted by title is
 * unreadable -- "Episode 10" sorts before "Episode 2" -- and because it is what
 * lets a later autoplay find the next episode offline.
 *
 * ## Concurrency
 *
 * The service writes and the UI reads, from different threads. Every operation
 * takes the same lock and rewrites the whole file: the index is a few dozen
 * rows of small strings, so the cost of being obviously correct is nothing.
 */
object Downloads {

    private const val TAG = "FlipFlex/dl"

    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val DONE = "done"
    const val FAILED = "failed"

    data class Entry(
        val ratingKey: String,
        val state: String,
        val title: String,
        val type: String,
        val showTitle: String = "",
        val seasonTitle: String = "",
        /** "S01E04". Empty for a film, and for anything with no index. */
        val code: String = "",
        val durationMs: Long = 0,
        /** Real size, once the file is complete. */
        val bytes: Long = 0,
        /**
         * Bitrate times runtime.
         *
         * Only ever used to draw a progress bar, and it does not need to be
         * accurate -- but a download with no visible progress is
         * indistinguishable from a stalled one, and this is the only way to
         * have any. The transcoder never declares a Content-Length for a stream
         * it is still producing.
         */
        val estBytes: Long = 0,
        /**
         * Where the user had got to when this was queued.
         *
         * Carried so the `stopped` timeline the service sends when a download
         * ends can report the real position. Sending zero there would clear the
         * resume point on the server, turning a failed download into lost
         * progress on every other client on the account.
         */
        val viewOffsetMs: Long = 0,
        val file: String = "",
        val addedAt: Long = 0,
        val summary: String = "",
    ) {
        val isShow: Boolean get() = showTitle.isNotEmpty()
    }

    private val lock = Any()

    /** Cached in memory so the browse screens do not read the file per row. */
    private var cache: MutableList<Entry>? = null

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "downloads").apply { mkdirs() }

    private fun indexFile(ctx: Context): File = File(dir(ctx), "index.json")

    fun fileFor(ctx: Context, entry: Entry): File = File(dir(ctx), entry.file)

    /** Where the download is written while it runs. Renamed on success. */
    fun partFor(ctx: Context, entry: Entry): File = File(dir(ctx), entry.file + ".part")

    // ---- reading -----------------------------------------------------------

    fun all(ctx: Context): List<Entry> = synchronized(lock) {
        cache?.let { return it.toList() }
        val loaded = read(ctx)
        cache = loaded
        loaded.toList()
    }

    fun get(ctx: Context, ratingKey: String): Entry? =
        all(ctx).firstOrNull { it.ratingKey == ratingKey }

    /** The local file for something, only if the download actually finished. */
    fun playableFile(ctx: Context, ratingKey: String): File? {
        val e = get(ctx, ratingKey) ?: return null
        if (e.state != DONE) return null
        val f = fileFor(ctx, e)
        return if (f.isFile && f.length() > 0) f else null
    }

    fun count(ctx: Context): Int = all(ctx).size

    fun countDone(ctx: Context): Int = all(ctx).count { it.state == DONE }

    /** Total bytes actually on disk. Queued rows have none and are not counted. */
    fun bytesOnDisk(ctx: Context): Long = all(ctx).filter { it.state == DONE }.sumOf { it.bytes }

    fun nextQueued(ctx: Context): Entry? = all(ctx).firstOrNull { it.state == QUEUED }

    // ---- writing -----------------------------------------------------------

    /**
     * Queue something. Returns false if it is already known, in any state --
     * including `failed`, which the caller is expected to clear first rather
     * than have a second Download press silently do nothing.
     */
    fun add(ctx: Context, entry: Entry): Boolean = synchronized(lock) {
        val list = cache ?: read(ctx).also { cache = it }
        if (list.any { it.ratingKey == entry.ratingKey }) return false
        list += entry
        write(ctx, list)
        true
    }

    fun setState(ctx: Context, ratingKey: String, state: String, bytes: Long = -1) =
        synchronized(lock) {
            val list = cache ?: read(ctx).also { cache = it }
            val i = list.indexOfFirst { it.ratingKey == ratingKey }
            if (i < 0) return
            list[i] = list[i].copy(
                state = state,
                bytes = if (bytes >= 0) bytes else list[i].bytes,
            )
            write(ctx, list)
        }

    /** Drop the row and the file with it. */
    fun remove(ctx: Context, ratingKey: String) = synchronized(lock) {
        val list = cache ?: read(ctx).also { cache = it }
        val i = list.indexOfFirst { it.ratingKey == ratingKey }
        if (i < 0) return
        val e = list.removeAt(i)
        fileFor(ctx, e).delete()
        partFor(ctx, e).delete()
        write(ctx, list)
    }

    /**
     * Put anything left mid-flight back in the queue.
     *
     * Called at startup. A row saying `downloading` with no service running is
     * what a crash, a battery pull or `adb install -r` leaves behind, and it
     * would otherwise sit there forever -- the service only ever picks up
     * `queued`. The fragment is deleted rather than kept: the transcode
     * endpoint serves one continuous stream and honours no byte ranges, so
     * there is no such thing as resuming from 40 MB.
     */
    fun recover(ctx: Context) = synchronized(lock) {
        val list = cache ?: read(ctx).also { cache = it }
        var changed = false
        list.forEachIndexed { i, e ->
            if (e.state == DOWNLOADING) {
                partFor(ctx, e).delete()
                list[i] = e.copy(state = QUEUED)
                changed = true
                Log.i(TAG, "re-queued interrupted download of ${e.title}")
            }
        }
        if (changed) write(ctx, list)
    }

    // ---- building an entry from a browse row --------------------------------

    /**
     * The hierarchy is read off the item here, once, rather than at draw time.
     *
     * An episode's own `title` is "The Bicameral Mind" and its `parentTitle` is
     * "Season 1" -- but Plex only fills those in on the endpoints that have
     * them. Continue Watching gives grandparentTitle; a season's children give
     * parentIndex. Both paths are covered because a download can be started
     * from either screen, and an entry that lost its show name would land at the
     * top level of the Downloads library next to the films.
     */
    fun entryFor(item: PlexItem, bitrateKbps: Int, summary: String): Entry {
        val code =
            if (item.parentIndex >= 0 && item.index >= 0) {
                "S%02dE%02d".format(item.parentIndex, item.index)
            } else {
                ""
            }
        val seconds = item.durationMs / 1000
        // Video bitrate plus a nominal 128 kbps of audio, in bytes.
        val est = if (seconds > 0) (bitrateKbps + 128L) * seconds / 8L * 1000L else 0L
        return Entry(
            ratingKey = item.ratingKey,
            state = QUEUED,
            title = item.title,
            type = item.type,
            showTitle = item.grandparentTitle,
            seasonTitle = item.parentTitle.ifEmpty {
                if (item.parentIndex >= 0) "Season ${item.parentIndex}" else ""
            },
            code = code,
            durationMs = item.durationMs,
            estBytes = est,
            viewOffsetMs = item.viewOffsetMs,
            file = fileName(item),
            addedAt = System.currentTimeMillis(),
            summary = summary,
        )
    }

    /**
     * Legible on a computer, unique on the phone.
     *
     * The title goes in so the folder means something when the storage is
     * mounted elsewhere; the ratingKey goes in because two shows genuinely do
     * both have an episode called "Pilot".
     */
    private fun fileName(item: PlexItem): String {
        val safe = item.title
            .map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }
            .joinToString("")
            .take(60)
            .trim('_')
            .ifEmpty { "item" }
        return "$safe-${item.ratingKey}.mkv"
    }

    // ---- persistence -------------------------------------------------------

    private fun read(ctx: Context): MutableList<Entry> {
        val f = indexFile(ctx)
        if (!f.isFile) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    ratingKey = o.optString("ratingKey"),
                    state = o.optString("state", QUEUED),
                    title = o.optString("title"),
                    type = o.optString("type"),
                    showTitle = o.optString("showTitle"),
                    seasonTitle = o.optString("seasonTitle"),
                    code = o.optString("code"),
                    durationMs = o.optLong("durationMs"),
                    bytes = o.optLong("bytes"),
                    estBytes = o.optLong("estBytes"),
                    viewOffsetMs = o.optLong("viewOffsetMs"),
                    file = o.optString("file"),
                    addedAt = o.optLong("addedAt"),
                    summary = o.optString("summary"),
                )
            }.toMutableList()
        } catch (e: Exception) {
            // A truncated index -- a power cut mid-write -- must not make the
            // app unopenable. Losing the list of downloads is recoverable by
            // queueing them again; a crash loop on startup is not.
            Log.w(TAG, "index unreadable, starting empty: ${e.message}")
            mutableListOf()
        }
    }

    private fun write(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("ratingKey", e.ratingKey)
                    .put("state", e.state)
                    .put("title", e.title)
                    .put("type", e.type)
                    .put("showTitle", e.showTitle)
                    .put("seasonTitle", e.seasonTitle)
                    .put("code", e.code)
                    .put("durationMs", e.durationMs)
                    .put("bytes", e.bytes)
                    .put("estBytes", e.estBytes)
                    .put("viewOffsetMs", e.viewOffsetMs)
                    .put("file", e.file)
                    .put("addedAt", e.addedAt)
                    .put("summary", e.summary)
            )
        }
        // Write beside it and rename, so a kill mid-write leaves the old index
        // rather than half of the new one. rename() is atomic on ext4.
        val tmp = File(dir(ctx), "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile(ctx))
    }

    /** "1.4 GB", "260 MB". */
    fun humanBytes(b: Long): String = when {
        b >= 1_000_000_000L -> "%.1f GB".format(b / 1_000_000_000f)
        b >= 1_000_000L -> "${b / 1_000_000} MB"
        b >= 1_000L -> "${b / 1_000} kB"
        else -> "$b B"
    }
}
