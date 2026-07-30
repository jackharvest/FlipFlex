package io.github.jackharvest.flipflex.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.github.jackharvest.flipflex.R
import io.github.jackharvest.flipflex.input.Action
import io.github.jackharvest.flipflex.plex.PlexItem
import io.github.jackharvest.flipflex.plex.PlexLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Every list of Plex content, in one screen.
 *
 * Continue Watching, a library's Recommended view, its A-Z, a show's seasons and
 * a season's episodes differ only in which endpoint fills them -- they render
 * identically and are navigated identically. One activity with a mode extra
 * rather than five near-copies, because the interesting differences are all in
 * the Options menu, not in the list.
 */
class BrowseActivity : FlipActivity() {

    companion object {
        const val MODE_ONDECK = "ondeck"
        const val MODE_RECENT = "recent"
        /** A library's default landing screen: grouped rows, no A-Z. */
        const val MODE_RECOMMENDED = "recommended"
        /** A library A-Z, paginated, with the letter rail. */
        const val MODE_SECTION = "section"
        const val MODE_CHILDREN = "children"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SECTION_TYPE = "sectionType"

        /** Roughly a screenful: about 7 rows fit the 270dp content area. */
        private const val PAGE_JUMP = 7

        /**
         * Fetch the next page once the cursor is this close to the end.
         *
         * Far enough ahead that the request is usually finished before the user
         * reaches the bottom, on a link where a page takes a second or two.
         */
        private const val PREFETCH_WITHIN = 12

        fun intent(
            ctx: Context,
            mode: String,
            key: String,
            title: String,
            sectionType: String = "",
        ): Intent = Intent(ctx, BrowseActivity::class.java)
            .putExtra(EXTRA_MODE, mode)
            .putExtra(EXTRA_KEY, key)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SECTION_TYPE, sectionType)
    }

    private lateinit var list: RowList
    private lateinit var rail: LinearLayout
    private lateinit var mode: String
    private lateinit var key: String
    private lateinit var title: String
    private lateinit var sectionType: String

    private val uri get() = store.serverUri
    private val token get() = store.serverToken

    // ---- paging state ------------------------------------------------------

    private val loaded = mutableListOf<PlexItem>()
    private var totalSize = 0
    private var loadingMore: Job? = null

    /** Null means the whole library; otherwise the letter being shown. */
    private var letter: String? = null

    // ---- rail state --------------------------------------------------------

    private var letters: List<String> = emptyList()
    private var railIndex = 0
    private var railFocused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ONDECK
        key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        sectionType = intent.getStringExtra(EXTRA_SECTION_TYPE).orEmpty()

        val body = layoutInflater.inflate(R.layout.browse_body, null)
        setBody(body)
        rail = body.findViewById(R.id.letter_rail)
        list = RowList(this)
        body.findViewById<FrameLayout>(R.id.browse_list_holder).addView(list)

        setHeader(title)
        setSoftKeys(left = getString(R.string.soft_home), right = getString(R.string.soft_options))

        list.onChoose = { _, row -> (row.payload as? PlexItem)?.let { open(it) } }
        list.onMove = { index, _ -> maybeLoadMore(index) }

        load()
    }

    // ---- loading -----------------------------------------------------------

    private fun load(keepSelection: Boolean = false) {
        val u = uri
        val t = token
        if (u == null || t == null) {
            showMessage(getString(R.string.msg_no_server))
            return
        }
        showMessage(null)
        setBusy(true)
        loaded.clear()
        totalSize = 0

        lifecycleScope.launch {
            when (mode) {
                MODE_RECOMMENDED -> loadRecommended(u, t)
                MODE_SECTION -> loadSectionPage(u, t, keepSelection)
                MODE_ONDECK -> flat(PlexLibrary.onDeck(u, t), keepSelection)
                MODE_RECENT -> flat(PlexLibrary.recentlyAdded(u, t), keepSelection)
                else -> flat(PlexLibrary.children(u, t, key), keepSelection)
            }
            setBusy(false)
        }
    }

    private fun flat(items: List<PlexItem>, keepSelection: Boolean) {
        loaded.clear()
        loaded += items
        totalSize = items.size
        if (items.isEmpty()) {
            showMessage(getString(R.string.msg_empty))
            list.submit(emptyList())
        } else {
            list.submit(items.map { it.toRow() }, keepSelection)
        }
    }

    /**
     * A library's Recommended view.
     *
     * Plex's own clients draw these groups as horizontal carousels. That needs
     * width and a pointer, and this screen has 240dp and a D-pad -- so the same
     * grouping is expressed vertically, with captions. Each group is capped at a
     * handful of entries; the full A-Z is one press away via Options.
     *
     * Groups are fetched sequentially rather than concurrently. Four small
     * requests on a home LAN are fast enough, and a sequential failure is
     * legible where four interleaved ones are not.
     */
    private suspend fun loadRecommended(u: String, t: String) {
        val deck = PlexLibrary.onDeckInSection(u, t, key, size = 6)
        // "Recently released" is only meaningful for television: it is ordered by
        // air date, which a film library does not have in any useful sense.
        val released =
            if (sectionType == "show") PlexLibrary.recentlyReleased(u, t, key, size = 6)
            else emptyList()
        val added = PlexLibrary.recentlyAddedInSection(u, t, key, size = 6)
        val viewed = PlexLibrary.recentlyViewed(u, t, key, size = 6)

        val rows = buildList {
            fun group(caption: String, items: List<PlexItem>) {
                if (items.isEmpty()) return
                add(RowList.Row(title = caption, isHeader = true))
                items.forEach { add(it.toRow()) }
            }
            group("CONTINUE WATCHING", deck)
            group("RECENTLY RELEASED", released)
            group("RECENTLY ADDED", added)
            group("RECENTLY WATCHED", viewed)
        }

        if (rows.isEmpty()) {
            // An empty Recommended view is not an error -- a library nobody has
            // touched has nothing to recommend. Send them to the A-Z, which is
            // never empty.
            showMessage("Nothing to recommend yet.\nPress OK for all titles.")
            list.submit(emptyList())
        } else {
            list.submit(rows)
        }
    }

    private suspend fun loadSectionPage(u: String, t: String, keepSelection: Boolean) {
        val page = fetchPage(u, t, 0)
        loaded += page.items
        totalSize = page.totalSize
        if (loaded.isEmpty()) {
            showMessage(getString(R.string.msg_empty))
            list.submit(emptyList())
        } else {
            list.submit(loaded.map { it.toRow() }, keepSelection)
            setHeader(headerText())
        }
        loadLetters(u, t)
    }

    private suspend fun fetchPage(u: String, t: String, offset: Int): PlexLibrary.Page {
        val l = letter
        return if (l == null) {
            PlexLibrary.sectionItems(u, t, key, offset)
        } else {
            PlexLibrary.byFirstCharacter(u, t, key, l, offset)
        }
    }

    /**
     * Append the next page when the cursor nears the end.
     *
     * The first version fetched exactly 60 items and treated that as the whole
     * library, so a TV library visibly stopped somewhere in the Bs with no way
     * to reach anything after it. This is the fix, and it is why
     * [PlexLibrary.Page] carries the server's own `totalSize`.
     */
    private fun maybeLoadMore(index: Int) {
        if (mode != MODE_SECTION) return
        if (loaded.size >= totalSize) return
        if (index < loaded.size - PREFETCH_WITHIN) return
        if (loadingMore?.isActive == true) return

        val u = uri ?: return
        val t = token ?: return
        loadingMore = lifecycleScope.launch {
            setBusy(true, "…")
            val page = fetchPage(u, t, loaded.size)
            loaded += page.items
            if (page.totalSize > 0) totalSize = page.totalSize
            setBusy(false)
            // keepSelection, because the user is mid-scroll and the cursor
            // jumping back to the top of a 600-title list would be worse than
            // not paging at all.
            list.submit(loaded.map { it.toRow() }, keepSelection = true)
            setHeader(headerText())
        }
    }

    private fun headerText(): String {
        val l = letter
        val base = if (l == null) title else "$title · $l"
        return if (totalSize > 0) "$base (${loaded.size}/$totalSize)" else base
    }

    // ---- the A-Z rail ------------------------------------------------------

    private suspend fun loadLetters(u: String, t: String) {
        letters = PlexLibrary.firstCharacters(u, t, key).map { it.first }
        if (letters.isEmpty()) {
            rail.visibility = View.GONE
            return
        }
        rail.visibility = View.VISIBLE
        rail.removeAllViews()
        letters.forEach { ch ->
            val tv = TextView(this).apply {
                text = ch
                textSize = 7f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.ff_text_dim))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            }
            rail.addView(tv)
        }
        paintRail()
    }

    private fun paintRail() {
        for (i in 0 until rail.childCount) {
            val tv = rail.getChildAt(i) as TextView
            val on = railFocused && i == railIndex
            tv.setBackgroundColor(if (on) getColor(R.color.ff_amber) else 0)
            tv.setTextColor(
                getColor(
                    when {
                        on -> R.color.ff_ground
                        letters.getOrNull(i) == letter -> R.color.ff_amber
                        else -> R.color.ff_text_dim
                    }
                )
            )
        }
    }

    private fun focusRail(on: Boolean) {
        if (letters.isEmpty()) return
        railFocused = on
        if (on) {
            railIndex = letters.indexOf(letter).takeIf { it >= 0 } ?: 0
            setSoftKeys(left = getString(R.string.soft_home), right = null)
        } else {
            setSoftKeys(left = getString(R.string.soft_home), right = getString(R.string.soft_options))
        }
        paintRail()
    }

    private fun jumpToLetter() {
        letter = letters.getOrNull(railIndex)
        focusRail(false)
        load()
    }

    // ---- row shaping -------------------------------------------------------

    /**
     * An episode needs a different row shape depending on where it is listed,
     * and getting this wrong wastes the only two lines a row has.
     *
     * On Continue Watching the show is what identifies the row -- an episode
     * title alone is useless there, because "Episode 14" appears in every show
     * on the server. Inside a season the show name is the one thing every row
     * already shares, so repeating it costs width and tells you nothing.
     *
     * Seasons get the same treatment for the opposite reason. Recently Added
     * returns *seasons*, whose title is "Season 10" -- which on its own names
     * nothing at all. The show goes on the first line and the season on the
     * second, and choosing it opens that season directly.
     */
    private fun PlexItem.toRow(): RowList.Row {
        val seasonEpisode = if (parentIndex >= 0 && index >= 0) "S$parentIndex · E$index" else ""
        val inSeason = mode == MODE_CHILDREN

        val rowTitle: String
        val rowSubtitle: String
        when {
            type == "episode" && !inSeason && grandparentTitle.isNotEmpty() -> {
                rowTitle = grandparentTitle
                rowSubtitle = listOf(seasonEpisode, title)
                    .filter { it.isNotEmpty() }
                    .joinToString("  ")
            }
            type == "episode" -> {
                rowTitle = title
                rowSubtitle = seasonEpisode
            }
            type == "season" && parentTitle.isNotEmpty() -> {
                rowTitle = parentTitle
                rowSubtitle = title
            }
            else -> {
                rowTitle = title
                rowSubtitle = subtitle()
            }
        }

        return RowList.Row(
            title = rowTitle,
            subtitle = rowSubtitle,
            trailing = if (watched && !inProgress) "✓" else "",
            time = timeLabel(),
            progress = progress,
            payload = this,
        )
    }

    private fun open(item: PlexItem) {
        when {
            item.isPlayable -> play(item, resume = item.inProgress)
            item.isContainer -> startActivity(
                intent(this, MODE_CHILDREN, item.ratingKey, item.displayTitle())
            )
        }
    }

    /** A season's own title is "Season 10"; prefix the show so the header names it. */
    private fun PlexItem.displayTitle(): String =
        if (type == "season" && parentTitle.isNotEmpty()) "$parentTitle · $title" else title

    private fun play(item: PlexItem, resume: Boolean) {
        startActivity(
            PlayerActivity.intent(
                this,
                ratingKey = item.ratingKey,
                title = item.title,
                subtitle = item.subtitle(),
                startMs = if (resume) item.viewOffsetMs else 0L,
            )
        )
    }

    // ---- options -----------------------------------------------------------

    override fun optionsHeading(): String = list.selectedRow()?.title.orEmpty()

    override fun optionsFor(): List<Option> {
        val u = uri ?: return emptyList()
        val t = token ?: return emptyList()
        val item = list.selectedRow()?.payload as? PlexItem

        return buildList {
            // View switching first: it applies to the screen rather than to a
            // row, and it is the reason a library opens on Recommended at all.
            if (mode == MODE_RECOMMENDED) {
                add(Option("All titles (A-Z)") {
                    startActivity(intent(this@BrowseActivity, MODE_SECTION, key, title, sectionType))
                })
            }
            if (mode == MODE_SECTION) {
                add(Option("Recommended") {
                    startActivity(intent(this@BrowseActivity, MODE_RECOMMENDED, key, title, sectionType))
                })
                if (letter != null) {
                    add(Option("All letters") { letter = null; load() })
                }
            }

            if (item == null) return@buildList

            if (item.isPlayable) {
                if (item.inProgress) {
                    add(Option("Resume") { play(item, resume = true) })
                    add(Option("Play from start") { play(item, resume = false) })
                } else {
                    add(Option("Play") { play(item, resume = false) })
                }
            }

            if (item.isContainer) {
                add(Option("Play next up") {
                    lifecycleScope.launch {
                        setBusy(true)
                        val leaves = PlexLibrary.allLeaves(u, t, item.ratingKey)
                        setBusy(false)
                        // The same rule Plex itself uses, so the two agree about
                        // where you are: the part-watched episode, else the first
                        // unwatched one.
                        val next = leaves.firstOrNull { it.inProgress }
                            ?: leaves.firstOrNull { !it.watched }
                            ?: leaves.firstOrNull()
                        next?.let { play(it, resume = it.inProgress) }
                    }
                })
                add(Option("Shuffle all") {
                    lifecycleScope.launch {
                        setBusy(true)
                        val leaves = PlexLibrary.allLeaves(u, t, item.ratingKey)
                        setBusy(false)
                        leaves.randomOrNull()?.let { play(it, resume = false) }
                    }
                })
            }

            // Getting from an episode on Continue Watching to the rest of its
            // run is otherwise a trip back to Home and down through a library.
            if (item.parentRatingKey.isNotEmpty() && item.type == "episode") {
                add(Option("Go to season") {
                    startActivity(
                        intent(
                            this@BrowseActivity, MODE_CHILDREN,
                            item.parentRatingKey, item.parentTitle.ifEmpty { "Season" },
                        )
                    )
                })
            }
            if (item.grandparentRatingKey.isNotEmpty() && item.type == "episode") {
                add(Option("Go to show") {
                    startActivity(
                        intent(
                            this@BrowseActivity, MODE_CHILDREN,
                            item.grandparentRatingKey, item.grandparentTitle.ifEmpty { "Show" },
                        )
                    )
                })
            }

            if (item.watched) {
                add(Option("Mark unwatched") {
                    lifecycleScope.launch {
                        PlexLibrary.markUnwatched(u, t, item.ratingKey)
                        load(keepSelection = true)
                    }
                })
            } else {
                add(Option("Mark watched") {
                    lifecycleScope.launch {
                        PlexLibrary.markWatched(u, t, item.ratingKey)
                        load(keepSelection = true)
                    }
                })
            }

            add(Option("Refresh") { load(keepSelection = true) })
        }
    }

    // ---- keys --------------------------------------------------------------

    override fun onAction(action: Action, keyCode: Int): Boolean {
        if (railFocused) {
            return when (action) {
                Action.UP -> { railIndex = (railIndex - 1).coerceAtLeast(0); paintRail(); true }
                Action.DOWN -> {
                    railIndex = (railIndex + 1).coerceAtMost(letters.size - 1); paintRail(); true
                }
                Action.SELECT -> { jumpToLetter(); true }
                Action.LEFT, Action.BACK -> { focusRail(false); true }
                else -> false
            }
        }

        return when (action) {
            Action.UP -> list.move(-1)
            Action.DOWN -> list.move(+1)
            // Right steps into the letter rail when there is one. Where there is
            // not, left and right page instead -- there is nothing else for them
            // to do, and a dead key on a device with this few is waste.
            Action.RIGHT -> if (letters.isNotEmpty()) { focusRail(true); true } else list.move(+PAGE_JUMP)
            Action.LEFT -> if (letters.isNotEmpty()) false else list.move(-PAGE_JUMP)
            // Star and hash page, always. They arrive on this handset, they have
            // no other job, and paging has to stay reachable once left and right
            // are spent on the rail.
            Action.STAR -> list.move(-PAGE_JUMP)
            Action.POUND -> list.move(+PAGE_JUMP)
            Action.SELECT -> if (list.rows.isEmpty()) {
                // An empty Recommended view offers the A-Z instead.
                if (mode == MODE_RECOMMENDED) {
                    startActivity(intent(this, MODE_SECTION, key, title, sectionType))
                } else {
                    load()
                }
                true
            } else {
                list.choose()
            }
            else -> false
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the player: the row just watched has a new resume
        // position. Recommended is rebuilt wholesale; a paged A-Z is left alone,
        // because re-fetching would throw away everything scrolled so far.
        if (mode == MODE_RECOMMENDED && list.rows.isNotEmpty()) load()
    }
}
