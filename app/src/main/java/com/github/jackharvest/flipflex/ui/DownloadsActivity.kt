package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.dl.DownloadService
import com.github.jackharvest.flipflex.dl.Downloads
import com.github.jackharvest.flipflex.input.Action
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Downloads library, browsed the way the online libraries are.
 *
 * ## Why it is a library and not a flat list of files
 *
 * Because that is what makes offline usable. Six downloads are fine as a list;
 * a series is not -- twenty-four episodes in one column, sorted by episode
 * title, is a screen nobody can read down. So this walks the same shape as the
 * server does: shows at the top level, then seasons, then episodes, with films
 * sitting alongside the shows. The point is that reaching an episode with the
 * radio off takes the same presses as reaching it with the radio on.
 *
 * Every part of that hierarchy is read out of the download index, which copied
 * it off the item when the download was queued. Nothing here touches the
 * network -- see [Downloads] for why that duplication is the whole feature.
 *
 * ## Why it is not BrowseActivity
 *
 * BrowseActivity is one activity for every *Plex* list because they genuinely
 * are one thing: paged, tabbed, with an A-Z rail and a container window. None
 * of that applies to a few dozen rows held in a local file, and folding this
 * into it would mean guarding all of it against a mode that has no server.
 */
class DownloadsActivity : FlipActivity() {

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"

        /**
         * Separator inside the path extra. A unit separator rather than "/",
         * because show and season names contain slashes and colons and very
         * little else -- "9/11: The Falling Man" would otherwise browse to a
         * folder that does not exist.
         */
        private const val SEP = "\u001F"

        fun intent(ctx: Context, path: String = "", title: String = "Downloads"): Intent =
            Intent(ctx, DownloadsActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_TITLE, title)
    }

    private lateinit var list: RowList
    private lateinit var path: String
    private lateinit var title: String

    /** A show or a season: something to step into. */
    private data class Folder(val path: String, val title: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Downloads"

        list = RowList(this)
        setBody(list)
        setHeader(title)
        setSoftKeys(left = getString(R.string.soft_home), right = getString(R.string.soft_options))
        list.onChoose = { _, row -> choose(row.payload) }
        render()
        watchProgress()
    }

    // ---- the tree ----------------------------------------------------------

    private fun parts(): List<String> = if (path.isEmpty()) emptyList() else path.split(SEP)

    private fun render() {
        val all = Downloads.all(this)
        if (all.isEmpty()) {
            showMessage(getString(R.string.downloads_empty))
            list.submit(emptyList())
            return
        }
        showMessage(null)

        val segments = parts()
        val rows = when (segments.size) {
            0 -> rootRows(all)
            1 -> seasonRows(all, segments[0])
            else -> episodeRows(all, segments[0], segments[1])
        }

        if (rows.isEmpty()) {
            // Everything under this folder was deleted while it was open --
            // from the Options menu here, or by "delete once watched". Saying so
            // is better than an empty list that looks like a failed load.
            showMessage(getString(R.string.downloads_empty))
            list.submit(emptyList())
            return
        }
        list.submit(rows, keepSelection = true)
        setHeader(headerText(all))
    }

    private fun headerText(all: List<Downloads.Entry>): String {
        if (path.isNotEmpty()) return title
        val done = all.count { it.state == Downloads.DONE }
        val pending = all.size - done
        val size = Downloads.humanBytes(Downloads.bytesOnDisk(this))
        return if (pending > 0) "Downloads · $size · $pending pending" else "Downloads · $size"
    }

    /** Shows as folders, films as rows, and anything in flight at the top. */
    private fun rootRows(all: List<Downloads.Entry>): List<RowList.Row> = buildList {
        // In-flight first, and separated. A queue is the thing people come to
        // this screen to look at when the Wi-Fi has been flaky, and burying it
        // under an alphabetical list of shows is how it gets missed.
        val pending = all.filter { it.state != Downloads.DONE }
        if (pending.isNotEmpty()) {
            add(RowList.Row(title = "IN PROGRESS", isHeader = true))
            pending.sortedBy { it.addedAt }.forEach { add(pendingRow(it)) }
        }

        val done = all.filter { it.state == Downloads.DONE }
        val shows = done.filter { it.isShow }.groupBy { it.showTitle }
        val films = done.filter { !it.isShow }

        if (shows.isNotEmpty()) {
            add(RowList.Row(title = "SHOWS", isHeader = true))
            shows.entries.sortedBy { it.key.lowercase() }.forEach { (show, eps) ->
                add(
                    RowList.Row(
                        title = show,
                        subtitle = "${eps.size} episode${if (eps.size == 1) "" else "s"}",
                        trailing = Downloads.humanBytes(eps.sumOf { it.bytes }),
                        payload = Folder(show, show),
                    )
                )
            }
        }

        if (films.isNotEmpty()) {
            add(RowList.Row(title = "FILMS", isHeader = true))
            films.sortedBy { it.title.lowercase() }.forEach { add(doneRow(it)) }
        }
    }

    private fun seasonRows(all: List<Downloads.Entry>, show: String): List<RowList.Row> {
        val eps = all.filter { it.state == Downloads.DONE && it.showTitle == show }
        val seasons = eps.groupBy { it.seasonTitle }
        // One season is not a level of navigation, it is a detour. Show its
        // episodes directly rather than making the user press OK on a list of
        // one, which is the same reasoning the season list uses upstream.
        if (seasons.size <= 1) return eps.sortedBy { it.code }.map { doneRow(it) }
        return seasons.entries
            .sortedBy { it.key }
            .map { (season, list) ->
                RowList.Row(
                    title = season.ifEmpty { "Episodes" },
                    subtitle = "${list.size} episode${if (list.size == 1) "" else "s"}",
                    trailing = Downloads.humanBytes(list.sumOf { it.bytes }),
                    payload = Folder("$show$SEP$season", season.ifEmpty { show }),
                )
            }
    }

    private fun episodeRows(all: List<Downloads.Entry>, show: String, season: String): List<RowList.Row> =
        all.filter { it.state == Downloads.DONE && it.showTitle == show && it.seasonTitle == season }
            // By code, never by title. "Episode 10" sorts before "Episode 2",
            // and a season nobody can read in order is a season nobody watches
            // in order.
            .sortedBy { it.code }
            .map { doneRow(it) }

    private fun doneRow(e: Downloads.Entry): RowList.Row = RowList.Row(
        title = e.title,
        subtitle = listOfNotNull(
            e.code.ifEmpty { null },
            if (e.durationMs > 0) "${e.durationMs / 60_000}m" else null,
        ).joinToString("  ·  "),
        trailing = Downloads.humanBytes(e.bytes),
        payload = e,
    )

    private fun pendingRow(e: Downloads.Entry): RowList.Row {
        val part = Downloads.partFor(this, e)
        val got = if (part.isFile) part.length() else 0L
        return RowList.Row(
            title = e.title,
            subtitle = when (e.state) {
                Downloads.DOWNLOADING -> "Downloading · ${Downloads.humanBytes(got)}"
                Downloads.FAILED -> "Failed · OK to retry"
                else -> "Queued"
            },
            // The estimate is bitrate times runtime, so this bar is honest about
            // roughly where a download is and dishonest about exactly where.
            // That is the right trade: no bar at all reads as a stalled
            // download, which is the one state it is meant to rule out.
            progress = if (e.estBytes > 0) (got.toFloat() / e.estBytes).coerceIn(0f, 1f) else 0f,
            payload = e,
        )
    }

    // ---- progress ----------------------------------------------------------

    /**
     * Repaint while something is downloading.
     *
     * Polling, rather than a binder or a broadcast from the service. What is
     * being watched is the size of a file on disk, which nothing sends an event
     * for, and the tick stops itself the moment nothing is in flight -- so the
     * cost is a `length()` call every two seconds on exactly the screen that
     * exists to show it.
     */
    private fun watchProgress() {
        lifecycleScope.launch {
            while (isActive) {
                delay(2_000)
                if (Downloads.all(this@DownloadsActivity).none { it.state != Downloads.DONE }) continue
                render()
            }
        }
    }

    // ---- actions -----------------------------------------------------------

    private fun choose(payload: Any?) {
        when (payload) {
            is Folder -> startActivity(intent(this, payload.path, payload.title))
            is Downloads.Entry -> when (payload.state) {
                Downloads.DONE -> startActivity(
                    DetailActivity.intent(this, payload.ratingKey, payload.title)
                )
                Downloads.FAILED -> {
                    // Retry is a re-queue of the same row rather than a new one:
                    // the row already carries the show and season names, and
                    // re-deriving them would need the server that is very
                    // possibly the reason it failed.
                    Downloads.setState(this, payload.ratingKey, Downloads.QUEUED)
                    DownloadService.start(this)
                    render()
                }
                else -> showTransientMessage("Already ${payload.state}.")
            }
        }
    }

    override fun optionsHeading(): String = list.selectedRow()?.title.orEmpty()

    override fun optionsFor(): List<Option> = buildList {
        when (val p = list.selectedRow()?.payload) {
            is Downloads.Entry -> {
                if (p.state == Downloads.DONE) {
                    add(Option("Play") {
                        startActivity(
                            PlayerActivity.intent(
                                this@DownloadsActivity,
                                ratingKey = p.ratingKey,
                                title = p.title,
                                subtitle = p.showTitle,
                                startMs = 0L,
                            )
                        )
                    })
                }
                add(Option("Delete") {
                    confirm("Delete ${p.title}?", "Delete") {
                        Downloads.remove(this@DownloadsActivity, p.ratingKey)
                        render()
                    }
                })
            }
            is Folder -> add(Option("Delete everything in here") {
                val victims = Downloads.all(this@DownloadsActivity).filter { under(it, p.path) }
                confirm("Delete ${victims.size} download${if (victims.size == 1) "" else "s"}?", "Delete") {
                    victims.forEach { Downloads.remove(this@DownloadsActivity, it.ratingKey) }
                    render()
                }
            })
            else -> Unit
        }
        if (Downloads.all(this@DownloadsActivity).any { it.state == Downloads.QUEUED }) {
            add(Option("Start queued downloads") { DownloadService.start(this@DownloadsActivity) })
        }
        add(Option("Refresh") { render() })
    }

    private fun under(e: Downloads.Entry, folder: String): Boolean {
        val segs = folder.split(SEP)
        return when (segs.size) {
            1 -> e.showTitle == segs[0]
            else -> e.showTitle == segs[0] && e.seasonTitle == segs[1]
        }
    }

    override fun onHeaderFocusChanged(on: Boolean) {
        list.parked = on
    }

    override fun onAction(action: Action, keyCode: Int): Boolean = when (action) {
        Action.UP -> list.move(-1) || focusHeader(true)
        Action.DOWN -> list.move(+1)
        // Left is up a level here too -- out of a season, out of a show, out of
        // Downloads -- which on a screen three levels deep is worth more than
        // the backwards page it used to be. Star still pages.
        Action.LEFT -> { goUp(); true }
        Action.STAR -> list.move(-7)
        Action.RIGHT, Action.POUND -> list.move(+7)
        Action.SELECT -> if (list.rows.isEmpty()) { render(); true } else list.choose()
        else -> false
    }

    override fun onResume() {
        super.onResume()
        render()
    }
}
