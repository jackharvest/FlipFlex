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
        /**
         * What the server actually sends, not what it was asked for.
         *
         * Measured rather than assumed: every path this app can request comes
         * back two-channel, including from an 8-channel E-AC3 source, and
         * neither `maxAudioChannels` nor a client profile extra moves it. HLS
         * gives AAC; the single-file download path gives MP3, which is why
         * [Downloads] does not reuse this.
         */
        private const val STREAM_AUDIO = "AAC Stereo"

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

    /**
     * ## Where the description sits, and why it is here rather than at the top
     *
     * It used to be the first thing on the list, above everything. That reads
     * beautifully on arrival and then becomes unreachable: the cursor lands on
     * the first *selectable* row, which is below the prose, and every scroll
     * pins that row to the top of the viewport -- so the moment you pressed
     * down, the description left the screen and no key would ever bring it
     * back. That was the report, and it was a real dead end rather than an
     * awkwardness. [RowList] now anchors at zero whenever nothing selectable
     * sits above the cursor, which fixes the dead end on its own.
     *
     * The order below then follows from what the cursor's home is worth. On a
     * phone that gets opened for four minutes, Play should be under the cursor
     * on arrival -- so it goes first, and the description immediately after it.
     * That keeps both on screen at once at the position the page opens in, and
     * puts the prose one press from the cursor's resting place instead of
     * behind it. The settings follow, because they are the rarer errand.
     *
     * Two columns -- prose on the right, controls on the left -- was tried and
     * abandoned: 240dp split two ways is about eighteen characters a line, and
     * a summary set that narrow is twelve ragged lines that fit on the screen
     * even less well than one column did.
     */
    private fun render(d: PlexDetail.Detail) {
        val item = d.item
        val local = Downloads.get(this, ratingKey)
        val saved = local?.state == Downloads.DONE

        val rows = buildList {
            add(
                RowList.Row(
                    title = factsLine(d),
                    compare = comparison(d, local),
                    isBlurb = true,
                )
            )

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

            if (d.summary.isNotEmpty()) {
                add(RowList.Row(title = d.summary, isBlurb = true))
            }

            if (saved) savedSection(local!!) else settingsSection(d, item, local)
        }

        list.submit(rows, keepSelection = true)
    }

    /**
     * What a copy on the phone is, rather than what it could be.
     *
     * A download is a finished transcode. Its resolution, its bitrate and
     * whether the subtitles are burned into the picture were decided when it
     * was queued and are now facts about the bytes on disk -- and the player
     * always prefers the local copy, so nothing on this page can change what
     * pressing Play produces. The page nevertheless went on offering a quality
     * picker and a subtitle switch for a downloaded episode: two controls that
     * appeared to be about the thing you were looking at, applied to some
     * hypothetical future streaming of it, and did nothing at all to the file.
     *
     * So the same three facts are shown instead of offered, as badges, and the
     * only action left is the only one that is real.
     */
    private fun MutableList<RowList.Row>.savedSection(local: Downloads.Entry) {
        add(RowList.Row(title = "ON THIS PHONE", isHeader = true))
        add(
            RowList.Row(
                title = if (local.seekable) {
                    "Plays with no server and no Wi-Fi."
                } else {
                    "Plays with no server and no Wi-Fi.\n" +
                        "Saved before seeking worked, so it cannot be skipped through. " +
                        "Download it again to fix that."
                },
                isBlurb = true,
            )
        )
        add(
            RowList.Row(
                title = "Delete this copy",
                subtitle = Downloads.humanBytes(local.bytes),
                accent = R.color.ff_badge_local,
                payload = Act { confirmDelete(local) },
            )
        )
    }

    /**
     * The three things people change, marked as a group.
     *
     * They were drawn identically to Play and to every navigation row above
     * them, so a list whose top half is "watch this" and whose bottom half is
     * "configure this" read as one undifferentiated column of eight. The
     * coloured stripe and the coloured value are what separate the two halves
     * without spending a caption or a screen on it.
     */
    private fun MutableList<RowList.Row>.settingsSection(
        d: PlexDetail.Detail,
        item: PlexItem,
        local: Downloads.Entry?,
    ) {
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
                    accent = R.color.ff_badge_subs,
                    payload = Act { chooseSubtitle(d) },
                )
            )
            if (d.audio.size > 1) {
                add(
                    RowList.Row(
                        title = "Audio",
                        trailing = shortLabel(d.selectedAudio?.label ?: "Default"),
                        accent = R.color.ff_badge_subs,
                        payload = Act { chooseAudio(d) },
                    )
                )
            }
        } else {
            add(
                RowList.Row(
                    title = "Subtitles",
                    trailing = if (store.subtitles) "On" else "Off",
                    accent = R.color.ff_badge_subs,
                    payload = Act { store.subtitles = !store.subtitles; render(d) },
                )
            )
        }

        val q = Quality.byId(store.quality)
        add(
            RowList.Row(
                title = "Streaming quality",
                subtitle = q.summary,
                trailing = q.label,
                accent = R.color.ff_badge_target,
                payload = Act { chooseQuality(d) },
            )
        )

        // The download quality lives here, next to the button that uses it,
        // rather than only in Settings. It was three screens away from the
        // moment anybody thinks about it, which is the moment they are looking
        // at something they want to take with them -- and a default nobody can
        // find is a default nobody changes. It is deliberately the same
        // system-wide preference the Settings screen edits, not a per-item one:
        // the next thing you download should behave the way the last one did.
        val dq = Quality.byId(store.downloadQuality)
        add(
            RowList.Row(
                title = "Download quality",
                subtitle = "${dq.summary} · all downloads",
                trailing = dq.label,
                accent = R.color.ff_badge_local,
                payload = Act { chooseDownloadQuality(d) },
            )
        )

        add(downloadRow(item, local))
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
     * What the file is, and what is about to reach the screen, side by side.
     *
     * ## Three versions of this, and why it ended up as two columns
     *
     * It began as one line of dim grey text -- `1080p · HEVC · 5.5 Mbps →
     * 320x240 800 kbps` -- directly above a summary in the same dim grey, which
     * made four separate facts into thirty characters of prose nobody reads.
     * Badges fixed the reading: colour separates what punctuation could not, and
     * the colours mean something fixed across the app -- blue is what the file
     * already is, green is what will reach the screen, violet is the copy on the
     * phone, rose is subtitles.
     *
     * What badges alone still did not say is that the second set is *produced
     * from* the first. Seven chips in a row is a list of facts; two columns with
     * an arrow is a sentence. That is [CompareStrip], and it is worth the extra
     * view because this is the one screen whose job is to set expectations about
     * a conversion that is about to happen.
     *
     * ## Why the audio is on both sides
     *
     * It is frequently the largest change and it was the one thing never shown.
     * An 8-channel E-AC3 source comes back **2-channel** on every path this app
     * can ask for -- measured, on both the HLS and the single-file paths, and
     * not something any parameter moves. Somebody who plugs in headphones
     * expecting surround should be able to see that before pressing Play, not
     * deduce it afterwards.
     *
     * A downloaded item reports its own numbers rather than the preset's,
     * because for that item they are facts about bytes on disk rather than a
     * prediction -- and because with the radio off they are the only ones there
     * are.
     */
    private fun comparison(
        d: PlexDetail.Detail,
        local: Downloads.Entry?,
    ): Pair<CompareStrip.Side, CompareStrip.Side> {
        val source = buildList {
            if (d.sourceResolution.isNotEmpty()) {
                add(BadgeStrip.Badge(d.sourceResolution, R.color.ff_badge_source))
            }
            if (d.videoCodec.isNotEmpty()) {
                add(BadgeStrip.Badge(d.videoCodec.uppercase(), R.color.ff_badge_source))
            }
            if (d.sourceBitrateKbps > 0) {
                add(BadgeStrip.Badge(mbps(d.sourceBitrateKbps), R.color.ff_badge_source))
            }
            if (d.audioLine().isNotEmpty()) {
                add(BadgeStrip.Badge(d.audioLine(), R.color.ff_badge_source))
            }
        }

        if (local?.state == Downloads.DONE) {
            return CompareStrip.Side("The file", source) to
                CompareStrip.Side("On this phone", offlineBadges(local))
        }

        // Direct play asks the server to send the file as it is, so the honest
        // right-hand column is the left-hand one over again -- in the colour
        // that means "this is what reaches the screen". Printing the quality
        // preset here would be printing a setting that is not in the path.
        if (store.directPlay) {
            return CompareStrip.Side("The file", source) to
                CompareStrip.Side(
                    "Direct play",
                    source.map { BadgeStrip.Badge(it.text, R.color.ff_badge_target) },
                )
        }

        val q = Quality.byId(store.quality)
        val target = buildList {
            add(BadgeStrip.Badge(q.resolution, R.color.ff_badge_target))
            add(BadgeStrip.Badge(mbps(q.bitrate), R.color.ff_badge_target))
            // AAC stereo is not a guess: it is what the server returns on the
            // HLS path for every source we have measured, including 8-channel
            // E-AC3. See the surround note in docs/phase2-playback.md.
            add(BadgeStrip.Badge(STREAM_AUDIO, R.color.ff_badge_target))
            if (subtitlesWillBurn(d)) {
                add(BadgeStrip.Badge("Subtitles", R.color.ff_badge_subs))
            }
        }
        return CompareStrip.Side("The file", source) to CompareStrip.Side("Plays as", target)
    }

    /**
     * Whether pressing Play right now would burn subtitles in.
     *
     * The same rule [play] uses, and it has to be: a strip that promises
     * subtitles and a playback that does not produce them is worse than showing
     * nothing at all. Per item and off the server where there is a part to read,
     * the global switch where there is not.
     */
    private fun subtitlesWillBurn(d: PlexDetail.Detail): Boolean =
        if (d.partId.isNotEmpty()) d.selectedSubtitle != null else store.subtitles

    private fun mbps(kbps: Int): String =
        if (kbps >= 1000) "%.1f Mbps".format(kbps / 1000f) else "$kbps kbps"

    private fun subtitleLabel(d: PlexDetail.Detail): String {
        val sel = d.selectedSubtitle ?: return "Off"
        return shortLabel(sel.label)
    }

    /** The trailing column is about 90dp. Anything longer is a truncated mess. */
    private fun shortLabel(s: String): String = if (s.length > 14) s.take(13) + "…" else s

    private fun downloadRow(item: PlexItem, local: Downloads.Entry?): RowList.Row = when {
        local?.state == Downloads.DOWNLOADING -> RowList.Row(
            title = "Downloading…",
            trailing = "Cancel",
            accent = R.color.ff_badge_local,
            payload = Act { Downloads.remove(this, ratingKey); reload() },
        )
        local?.state == Downloads.QUEUED -> RowList.Row(
            title = "Queued",
            trailing = "Cancel",
            accent = R.color.ff_badge_local,
            payload = Act { Downloads.remove(this, ratingKey); reload() },
        )
        local?.state == Downloads.FAILED -> RowList.Row(
            title = "Download failed",
            trailing = "Retry",
            accent = R.color.ff_badge_local,
            payload = Act { Downloads.remove(this, ratingKey); queue(item) },
        )
        item.isContainer -> RowList.Row(
            title = "Download all episodes",
            accent = R.color.ff_badge_local,
            payload = Act { queueAll(item) },
        )
        else -> RowList.Row(
            title = "Download",
            accent = R.color.ff_badge_local,
            payload = Act { queue(item) },
        )
    }

    /**
     * Offline, with only what the download index remembers.
     *
     * Same order as the online page -- facts, Play, description, the one real
     * action -- so that having no server changes what is on the screen without
     * changing where anything is.
     */
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
                        badges = offlineBadges(local),
                        isBlurb = true,
                    )
                )
                add(RowList.Row(title = "PLAY", isHeader = true))
                add(
                    RowList.Row(
                        title = "Play",
                        payload = Act {
                            // Local file, so no guard: startPlayback would let
                            // it through anyway, and going through it here would
                            // only be a way for the check to be wrong one day.
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
                if (local.summary.isNotEmpty()) {
                    add(RowList.Row(title = local.summary, isBlurb = true))
                }
                add(RowList.Row(title = "ON THIS PHONE", isHeader = true))
                add(
                    RowList.Row(
                        title = "The server is not reachable, so this is the only copy available.",
                        isBlurb = true,
                    )
                )
                add(
                    RowList.Row(
                        title = "Delete this copy",
                        subtitle = Downloads.humanBytes(local.bytes),
                        accent = R.color.ff_badge_local,
                        payload = Act { confirmDelete(local) },
                    )
                )
            },
            keepSelection = true,
        )
    }

    private fun offlineBadges(local: Downloads.Entry): List<BadgeStrip.Badge> = buildList {
        add(BadgeStrip.Badge(Downloads.humanBytes(local.bytes), R.color.ff_badge_local))
        if (local.resolution.isNotEmpty()) {
            add(BadgeStrip.Badge(local.resolution, R.color.ff_badge_target))
        }
        if (local.bitrateKbps > 0) {
            add(BadgeStrip.Badge(mbps(local.bitrateKbps), R.color.ff_badge_target))
        }
        if (local.subtitlesBurned) add(BadgeStrip.Badge("Subtitles", R.color.ff_badge_subs))
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
        startPlayback(
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
            "Streaming quality",
            Quality.PRESETS.map { p ->
                Option(if (p.id == current.id) "${p.label} · ${p.summary}  ✓" else "${p.label} · ${p.summary}") {
                    store.quality = p.id
                    render(d)
                }
            },
            startAt = Quality.PRESETS.indexOfFirst { it.id == current.id }.coerceAtLeast(0),
        )
    }

    /**
     * The download preset, changed from the page you decide to download on.
     *
     * The same system-wide value the Settings screen edits, deliberately -- a
     * per-item download quality would mean a Downloads folder whose files are
     * all different and none of them what you expected. What was wrong before
     * was only where it lived: three screens from the button that uses it.
     */
    private fun chooseDownloadQuality(d: PlexDetail.Detail) {
        val current = Quality.byId(store.downloadQuality)
        chooseFrom(
            "Download quality",
            Quality.PRESETS.map { p ->
                Option(if (p.id == current.id) "${p.label} · ${p.summary}  ✓" else "${p.label} · ${p.summary}") {
                    store.downloadQuality = p.id
                    render(d)
                }
            },
            startAt = Quality.PRESETS.indexOfFirst { it.id == current.id }.coerceAtLeast(0),
        )
    }

    // ---- downloads ---------------------------------------------------------

    private fun queue(item: PlexItem) = startDownload { waitsForWifi ->
        val q = Quality.byId(store.downloadQuality)
        // The subtitle decision is resolved here, once, and stored on the row.
        // It is burned into the picture by the transcoder, so it is a property
        // of the file from the moment it is queued -- and it has to be read the
        // same way playing it would read it, per item and off the server, or a
        // download of something with an English track selected on a TV would
        // silently arrive without it.
        val burn = detail?.takeIf { it.partId.isNotEmpty() }
            ?.let { it.selectedSubtitle != null }
            ?: store.subtitles
        val added = Downloads.add(this, Downloads.entryFor(item, q, burn, detail?.summary.orEmpty()))
        // Started even when it will not run yet: the service reaches the same
        // Wi-Fi check, logs it and stops, which costs nothing and keeps one
        // decision in one place.
        if (added) DownloadService.start(this)
        reload()
        if (added && waitsForWifi) showTransientMessage(getString(R.string.net_download_waiting))
    }

    /**
     * Queue a whole show or season.
     *
     * Every episode is fetched first, because [Downloads.entryFor] copies the
     * show and season names onto each row -- that duplication is what lets the
     * Downloads library be browsed with the radio off, and it can only be done
     * while there is still a server to ask.
     */
    private fun queueAll(item: PlexItem) = startDownload { waitsForWifi ->
        val u = uri ?: return@startDownload
        val t = token ?: return@startDownload
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
            // A whole season has no one part to read a subtitle choice off, so
            // the global default is the only honest answer here.
            val n = eps.count {
                Downloads.add(
                    this@DetailActivity,
                    Downloads.entryFor(it, q, store.subtitles, ""),
                )
            }
            if (n > 0) DownloadService.start(this@DetailActivity)
            showTransientMessage(
                when {
                    n == 0 -> "Already queued."
                    waitsForWifi ->
                        "Queued $n episode${if (n == 1) "" else "s"}.\n\n" +
                            getString(R.string.net_download_waiting)
                    else -> "Queued $n episode${if (n == 1) "" else "s"}."
                }
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

    override fun onHeaderFocusChanged(on: Boolean) {
        list.parked = on
    }

    override fun onAction(action: Action, keyCode: Int): Boolean = when (action) {
        // Off the top of this list is the header, and OK there goes back to the
        // season or the library this was opened from. There is no tab strip in
        // between, so it is one press from the description.
        Action.UP -> list.move(-1) || focusHeader(true)
        Action.DOWN -> list.move(+1)
        // Left is up a level, the same as the back arrow, everywhere but the
        // player. This page is the most likely place to want it: it is where
        // you land from a list and where you go back to it from.
        Action.LEFT -> { goUp(); true }
        Action.STAR -> list.move(-5)
        Action.RIGHT, Action.POUND -> list.move(+5)
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
