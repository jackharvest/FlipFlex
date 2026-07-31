package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.dl.DownloadService
import com.github.jackharvest.flipflex.dl.Downloads
import com.github.jackharvest.flipflex.input.Action
import com.github.jackharvest.flipflex.plex.PlexDetail
import com.github.jackharvest.flipflex.plex.PlexItem
import com.github.jackharvest.flipflex.plex.PlexLibrary
import com.github.jackharvest.flipflex.plex.Quality
import kotlinx.coroutines.launch

/**
 * The page between choosing something and watching it.
 *
 * ## Why this screen exists
 *
 * Before it, choosing an episode started a transcode. Everything Plex knows
 * about an item -- what it is about, what the file actually is, which subtitle
 * and audio tracks it has -- was unreachable from the handset, and the two
 * decisions people most often want to make (subtitles on, and not at 800 kbps
 * over a hotspot) were buried in a settings screen three levels away from the
 * moment they think of them. PocketFlex learned this the same way and put its
 * subtitle switch on its pre-play card for exactly this reason: the difference
 * between a setting that exists and a setting that gets used is whether it is
 * on the screen you are already looking at.
 *
 * ## Why it is a list and not a poster
 *
 * Because it has to scroll, and this device has one cursor. A summary is four
 * to six wrapped lines at 10sp, which is most of a 270dp content area on its
 * own -- so the description and the actions cannot both be permanently visible,
 * and a scrolling text view above a list means two things competing for the
 * down key with nothing on screen saying which one has it. One list, with the
 * prose as an unselectable row, gives one cursor and one meaning for every key.
 *
 * ## What is deliberately still one press away
 *
 * The Options menu on the browse screens keeps its own Play and Resume entries.
 * This page is the default path, not the only one, and somebody who opens the
 * phone to resume the same show every evening should not have gained a press.
 * `Store.showDetails` turns the whole thing off.
 */
class DetailActivity : FlipActivity() {

    companion object {
        private const val EXTRA_KEY = "ratingKey"
        private const val EXTRA_TITLE = "title"

        fun intent(ctx: Context, ratingKey: String, title: String): Intent =
            Intent(ctx, DetailActivity::class.java)
                .putExtra(EXTRA_KEY, ratingKey)
                .putExtra(EXTRA_TITLE, title)
    }

    private lateinit var list: RowList
    private lateinit var ratingKey: String
    private lateinit var title: String

    private var detail: PlexDetail.Detail? = null

    /** Episodes of a show or season, fetched only when a download is asked for. */
    private var leaves: List<PlexItem>? = null


    private val uri get() = store.serverUri
    private val token get() = store.serverToken

    /** What a row does. Held in the row payload. */
    private data class Act(val run: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ratingKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        list = RowList(this)
        setBody(list)
        setHeader(title)
        setSoftKeys(left = getString(R.string.soft_home), right = getString(R.string.soft_options))
        list.onChoose = { _, row -> (row.payload as? Act)?.run?.invoke() }
        load()
    }

    private fun load() {
        val u = uri
        val t = token
        val local = Downloads.get(this, ratingKey)

        if (u == null || t == null) {
            // Offline, but the item may still be on the phone. A details page
            // that refuses to draw for something you already downloaded is the
            // one case where "no server" is the wrong answer.
            if (local != null) renderOffline(local) else showMessage(getString(R.string.msg_no_server))
            return
        }

        showMessage(null)
        setBusy(true)
        lifecycleScope.launch {
            val d = PlexDetail.fetch(u, t, ratingKey)
            setBusy(false)
            if (d == null) {
                if (local != null) renderOffline(local)
                else showMessage(getString(R.string.msg_offline))
                return@launch
            }
            detail = d
            setHeader(d.item.title.ifEmpty { title })
            render(d)
        }
    }

    // ---- rendering ---------------------------------------------------------

    private fun render(d: PlexDetail.Detail) {
        val item = d.item
        val local = Downloads.get(this, ratingKey)
        val q = Quality.byId(store.quality)

        val rows = buildList {
            add(RowList.Row(title = factsLine(d), isBlurb = true))
            add(RowList.Row(title = pipelineLine(d, q, local), isBlurb = true))
            if (d.summary.isNotEmpty()) {
                add(RowList.Row(title = d.summary, isBlurb = true))
            }

            if (item.isPlayable) {
                add(RowList.Row(title = "PLAY", isHeader = true))
                if (item.inProgress) {
                    add(
                        RowList.Row(
                            title = "Resume",
                            time = item.timeLabel(),
                            progress = item.progress,
                            payload = Act { play(item, resume = true) },
                        )
                    )
                    add(RowList.Row(title = "Play from start", payload = Act { play(item, false) }))
                } else {
                    add(
                        RowList.Row(
                            title = "Play",
                            time = item.timeLabel(),
                            payload = Act { play(item, resume = false) },
                        )
                    )
                }
            } else if (item.isContainer) {
                add(RowList.Row(title = "PLAY", isHeader = true))
                add(RowList.Row(title = "Play next up", payload = Act { playNextUp() }))
                add(RowList.Row(title = "Shuffle", payload = Act { shuffleHere() }))
                add(
                    RowList.Row(
                        title = "All episodes",
                        payload = Act {
                            startActivity(
                                BrowseActivity.intent(
                                    this@DetailActivity,
                                    BrowseActivity.MODE_CHILDREN,
                                    ratingKey,
                                    item.title,
                                )
                            )
                        },
                    )
                )
            }

            add(RowList.Row(title = "SETTINGS FOR THIS", isHeader = true))

            // Subtitles and audio are per-item and server-side, so they are only
            // offered where there is a part to apply them to. On a show or a
            // season the global switch in Settings is the only sensible answer,
            // and offering a track picker with nothing in it would be worse than
            // not offering one.
            if (d.partId.isNotEmpty()) {
                add(
                    RowList.Row(
                        title = "Subtitles",
                        trailing = subtitleLabel(d),
                        payload = Act { chooseSubtitle(d) },
                    )
                )
                if (d.audio.size > 1) {
                    add(
                        RowList.Row(
                            title = "Audio",
                            trailing = shortLabel(d.selectedAudio?.label ?: "Default"),
                            payload = Act { chooseAudio(d) },
                        )
                    )
                }
            } else {
                add(
                    RowList.Row(
                        title = "Subtitles",
                        trailing = if (store.subtitles) "On" else "Off",
                        payload = Act { store.subtitles = !store.subtitles; render(d) },
                    )
                )
            }

            add(
                RowList.Row(
                    title = "Quality",
                    subtitle = "Applies to all playback",
                    trailing = q.label,
                    payload = Act { chooseQuality(d) },
                )
            )

            add(downloadRow(item, local))
        }

        list.submit(rows, keepSelection = true)
    }

    /** "S1 · E4 · 2016 · 62m · TV-MA" -- whatever of it the server knows. */
    private fun factsLine(d: PlexDetail.Detail): String {
        val item = d.item
        return buildList {
            when (item.type) {
                "episode" -> {
                    if (item.grandparentTitle.isNotEmpty()) add(item.grandparentTitle)
                    if (item.parentIndex >= 0 && item.index >= 0) {
                        add("S${item.parentIndex} · E${item.index}")
                    }
                }
                "season" -> if (item.parentTitle.isNotEmpty()) add(item.parentTitle)
                "show" -> if (item.leafCount > 0) add("${item.leafCount} episodes")
                else -> Unit
            }
            if (item.year > 0) add(item.year.toString())
            if (item.durationMs > 0) add(item.timeLabel())
            if (d.contentRating.isNotEmpty()) add(d.contentRating)
        }.joinToString("  ·  ")
    }

    /**
     * What is about to happen to the pixels, said plainly.
     *
     * This is the line the details page was asked for: the source is 1080p, the
     * screen is 240 wide, and the server is going to make the difference go
     * away. Worth stating rather than implying, because it is the answer to
     * "why does this look soft" and to "why is direct play not offered" at the
     * same time -- pulling a 1080p file across the radio to draw it into 320x180
     * would cost more bandwidth and more battery for the same picture.
     */
    private fun pipelineLine(
        d: PlexDetail.Detail,
        q: Quality.Preset,
        local: Downloads.Entry?,
    ): String {
        if (local?.state == Downloads.DONE) {
            return "Saved on this phone · ${Downloads.humanBytes(local.bytes)}\nPlays with no server and no Wi-Fi."
        }
        val source = d.sourceLine().ifEmpty { "Source" }
        return "$source  →  ${q.resolution} ${q.bitrate} kbps"
    }

    private fun subtitleLabel(d: PlexDetail.Detail): String {
        val sel = d.selectedSubtitle ?: return "Off"
        return shortLabel(sel.label)
    }

    /** The trailing column is about 90dp. Anything longer is a truncated mess. */
    private fun shortLabel(s: String): String = if (s.length > 14) s.take(13) + "…" else s

    private fun downloadRow(item: PlexItem, local: Downloads.Entry?): RowList.Row = when {
        local?.state == Downloads.DONE -> RowList.Row(
            title = "Downloaded",
            subtitle = Downloads.humanBytes(local.bytes),
            trailing = "Delete",
            payload = Act { confirmDelete(local) },
        )
        local?.state == Downloads.DOWNLOADING -> RowList.Row(
            title = "Downloading…",
            trailing = "Cancel",
            payload = Act { Downloads.remove(this, ratingKey); reload() },
        )
        local?.state == Downloads.QUEUED -> RowList.Row(
            title = "Queued",
            trailing = "Cancel",
            payload = Act { Downloads.remove(this, ratingKey); reload() },
        )
        local?.state == Downloads.FAILED -> RowList.Row(
            title = "Download failed",
            trailing = "Retry",
            payload = Act { Downloads.remove(this, ratingKey); queue(item) },
        )
        item.isContainer -> RowList.Row(
            title = "Download all episodes",
            subtitle = qualityNote(),
            payload = Act { queueAll(item) },
        )
        else -> RowList.Row(
            title = "Download",
            subtitle = qualityNote(),
            payload = Act { queue(item) },
        )
    }

    private fun qualityNote(): String {
        val q = Quality.byId(store.downloadQuality)
        return "${q.label} · ${q.resolution}"
    }

    /** Offline, with only what the download index remembers. */
    private fun renderOffline(local: Downloads.Entry) {
        showMessage(null)
        setHeader(local.title)
        list.submit(
            buildList {
                add(
                    RowList.Row(
                        title = listOfNotNull(
                            local.showTitle.ifEmpty { null },
                            local.code.ifEmpty { null },
                        ).joinToString("  ·  "),
                        isBlurb = true,
                    )
                )
                add(
                    RowList.Row(
                        title = "Saved on this phone · ${Downloads.humanBytes(local.bytes)}\n" +
                            "The server is not reachable, so only the copy on this phone is available.",
                        isBlurb = true,
                    )
                )
                if (local.summary.isNotEmpty()) {
                    add(RowList.Row(title = local.summary, isBlurb = true))
                }
                add(RowList.Row(title = "PLAY", isHeader = true))
                add(
                    RowList.Row(
                        title = "Play",
                        payload = Act {
                            startActivity(
                                PlayerActivity.intent(
                                    this@DetailActivity,
                                    ratingKey = local.ratingKey,
                                    title = local.title,
                                    subtitle = local.showTitle,
                                    startMs = 0L,
                                )
                            )
                        },
                    )
                )
                add(
                    RowList.Row(
                        title = "Delete download",
                        subtitle = Downloads.humanBytes(local.bytes),
                        payload = Act { confirmDelete(local) },
                    )
                )
            },
            keepSelection = true,
        )
    }

    // ---- actions -----------------------------------------------------------

    private fun reload() {
        detail?.let { render(it) } ?: load()
    }

    /**
     * The subtitle decision is passed explicitly, not left to the global flag.
     *
     * This screen has just shown the user which track is selected -- read off
     * the server, where Plex keeps it per item -- so what it launches has to
     * match what it displayed. `detail` is null only on the container path,
     * where there is no part and no track to have selected.
     */
    private fun play(item: PlexItem, resume: Boolean) {
        val d = detail
        val burn = if (d != null && d.partId.isNotEmpty()) d.selectedSubtitle != null else null
        startActivity(
            PlayerActivity.intent(
                this,
                ratingKey = item.ratingKey,
                title = item.title,
                subtitle = item.subtitle(),
                startMs = if (resume) item.viewOffsetMs else 0L,
                burnSubtitles = burn,
            )
        )
    }

    private fun playNextUp() {
        val u = uri ?: return
        val t = token ?: return
        lifecycleScope.launch {
            setBusy(true)
            val all = PlexLibrary.allLeaves(u, t, ratingKey).filter { it.isPlayable }
            setBusy(false)
            val next = all.firstOrNull { it.inProgress }
                ?: all.firstOrNull { !it.watched }
                ?: all.firstOrNull()
            if (next == null) showTransientMessage("Nothing to play here.")
            else play(next, resume = next.inProgress)
        }
    }

    private fun shuffleHere() {
        val u = uri ?: return
        val t = token ?: return
        lifecycleScope.launch {
            setBusy(true)
            val pick = PlexLibrary.allLeaves(u, t, ratingKey).filter { it.isPlayable }.randomOrNull()
            setBusy(false)
            if (pick == null) showTransientMessage("Nothing to shuffle here.")
            else play(pick, resume = false)
        }
    }

    // ---- the choosers ------------------------------------------------------

    private fun chooseSubtitle(d: PlexDetail.Detail) {
        if (d.subtitles.isEmpty()) {
            showTransientMessage("This file has no\nsubtitle tracks.")
            return
        }
        val entries = buildList {
            add(Option(if (d.selectedSubtitle == null) "Off  ✓" else "Off") { applySubtitle(d, "0") })
            d.subtitles.forEach { tr ->
                add(Option(if (tr.selected) "${tr.label}  ✓" else tr.label) {
                    applySubtitle(d, tr.id)
                })
            }
        }
        // Open on whatever is already chosen. A file with nine tracks is a list
        // you cannot navigate if it always starts at the top.
        val at = d.subtitles.indexOfFirst { it.selected }.let { if (it < 0) 0 else it + 1 }
        chooseFrom("Subtitles", entries, startAt = at)
    }

    private fun applySubtitle(d: PlexDetail.Detail, streamId: String) {
        val u = uri ?: return
        val t = token ?: return
        setBusy(true)
        lifecycleScope.launch {
            PlexDetail.setSubtitle(u, t, d.partId, streamId)
            // Also move the global default, so the *next* thing played without
            // its own choice behaves the way this one just did. Without it,
            // turning subtitles on here and then playing the next episode --
            // which has its own untouched part -- silently gives no subtitles.
            store.subtitles = streamId != "0"
            // Re-fetch rather than patching the model: the server decides which
            // track ends up selected, and on a file with forced subtitles that
            // is not always the one that was asked for.
            load()
        }
    }

    private fun chooseAudio(d: PlexDetail.Detail) {
        val entries = d.audio.map { tr ->
            Option(if (tr.selected) "${tr.label}  ✓" else tr.label) {
                val u = uri ?: return@Option
                val t = token ?: return@Option
                setBusy(true)
                lifecycleScope.launch {
                    PlexDetail.setAudio(u, t, d.partId, tr.id)
                    load()
                }
            }
        }
        chooseFrom("Audio", entries, startAt = d.audio.indexOfFirst { it.selected }.coerceAtLeast(0))
    }

    private fun chooseQuality(d: PlexDetail.Detail) {
        val current = Quality.byId(store.quality)
        chooseFrom(
            "Quality",
            Quality.PRESETS.map { p ->
                Option(if (p.id == current.id) "${p.label} · ${p.summary}  ✓" else "${p.label} · ${p.summary}") {
                    store.quality = p.id
                    render(d)
                }
            },
            startAt = Quality.PRESETS.indexOfFirst { it.id == current.id }.coerceAtLeast(0),
        )
    }

    // ---- downloads ---------------------------------------------------------

    private fun queue(item: PlexItem) {
        val q = Quality.byId(store.downloadQuality)
        val added = Downloads.add(
            this,
            Downloads.entryFor(item, q.bitrate, detail?.summary.orEmpty()),
        )
        if (added) DownloadService.start(this)
        reload()
    }

    /**
     * Queue a whole show or season.
     *
     * Every episode is fetched first, because [Downloads.entryFor] copies the
     * show and season names onto each row -- that duplication is what lets the
     * Downloads library be browsed with the radio off, and it can only be done
     * while there is still a server to ask.
     */
    private fun queueAll(item: PlexItem) {
        val u = uri ?: return
        val t = token ?: return
        lifecycleScope.launch {
            setBusy(true)
            val eps = leaves ?: PlexLibrary.allLeaves(u, t, item.ratingKey)
                .filter { it.isPlayable }
                .also { leaves = it }
            setBusy(false)
            if (eps.isEmpty()) {
                showTransientMessage("Nothing here to download.")
                return@launch
            }
            val q = Quality.byId(store.downloadQuality)
            val n = eps.count { Downloads.add(this@DetailActivity, Downloads.entryFor(it, q.bitrate, "")) }
            if (n > 0) DownloadService.start(this@DetailActivity)
            showTransientMessage(
                if (n == 0) "Already queued." else "Queued $n episode${if (n == 1) "" else "s"}."
            )
            reload()
        }
    }

    private fun confirmDelete(local: Downloads.Entry) {
        confirm("Delete the copy on this phone?", "Delete") {
            Downloads.remove(this, local.ratingKey)
            reload()
        }
    }

    // ---- options -----------------------------------------------------------

    override fun optionsHeading(): String = detail?.item?.title ?: title

    override fun optionsFor(): List<Option> {
        val u = uri ?: return emptyList()
        val t = token ?: return emptyList()
        val item = detail?.item ?: return emptyList()

        return buildList {
            if (item.parentRatingKey.isNotEmpty() && item.type == "episode") {
                add(Option("Go to season") {
                    startActivity(
                        BrowseActivity.intent(
                            this@DetailActivity, BrowseActivity.MODE_CHILDREN,
                            item.parentRatingKey, item.parentTitle.ifEmpty { "Season" },
                        )
                    )
                })
            }
            if (item.grandparentRatingKey.isNotEmpty() && item.type == "episode") {
                add(Option("Go to show") {
                    startActivity(
                        BrowseActivity.intent(
                            this@DetailActivity, BrowseActivity.MODE_CHILDREN,
                            item.grandparentRatingKey, item.grandparentTitle.ifEmpty { "Show" },
                        )
                    )
                })
            }
            if (item.watched) {
                add(Option("Mark unwatched") {
                    lifecycleScope.launch { PlexLibrary.markUnwatched(u, t, ratingKey); load() }
                })
            } else {
                add(Option("Mark watched") {
                    lifecycleScope.launch { PlexLibrary.markWatched(u, t, ratingKey); load() }
                })
            }
            add(Option("Refresh") { load() })
        }
    }

    // ---- keys --------------------------------------------------------------

    override fun onAction(action: Action, keyCode: Int): Boolean = when (action) {
        Action.UP -> list.move(-1)
        Action.DOWN -> list.move(+1)
        Action.STAR -> list.move(-5)
        Action.POUND -> list.move(+5)
        Action.SELECT -> if (list.rows.isEmpty()) { load(); true } else list.choose()
        else -> false
    }

    override fun onResume() {
        super.onResume()
        // Back from the player, or from a download finishing while this screen
        // was in the background: the resume position and the download state are
        // both stale.
        if (list.rows.isNotEmpty()) load()
    }
}
