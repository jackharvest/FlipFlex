package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.input.Action
import com.github.jackharvest.flipflex.input.KeyMap
import com.github.jackharvest.flipflex.plex.PlexItem
import com.github.jackharvest.flipflex.plex.PlexLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Every list of Plex content, in one screen.
 *
 * Continue Watching, a library's Recommended view, its A-Z, its categories, a
 * show's seasons and a season's episodes differ only in which endpoint fills
 * them -- they render identically and are navigated identically. One activity
 * with a mode extra rather than seven near-copies, because the interesting
 * differences are all in the Options menu, not in the list.
 */
class BrowseActivity : FlipActivity() {

    companion object {
        const val MODE_ONDECK = "ondeck"
        const val MODE_RECENT = "recent"
        /** A library's default landing screen: grouped rows, no A-Z. */
        const val MODE_RECOMMENDED = "recommended"
        /** A library A-Z, paginated, with the letter rail. */
        const val MODE_SECTION = "section"
        /** The genres in a library. */
        const val MODE_CATEGORIES = "categories"
        /** One Recommended group in full, reached from its "more" button. */
        const val MODE_GROUP = "group"
        const val MODE_CHILDREN = "children"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SECTION_TYPE = "sectionType"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_GROUP = "group"

        /** Which Recommended group [MODE_GROUP] is showing in full. */
        const val GROUP_ONDECK = "ondeck"
        const val GROUP_RELEASED = "released"
        const val GROUP_ADDED = "added"
        const val GROUP_VIEWED = "viewed"

        /** Roughly a screenful: about 7 rows fit the 270dp content area. */
        private const val PAGE_JUMP = 7

        /**
         * Fetch the next page once the cursor is this close to the end.
         *
         * Far enough ahead that the request is usually finished before the user
         * reaches the bottom, on a link where a page takes a second or two.
         */
        private const val PREFETCH_WITHIN = 12

        /**
         * Rows shown per Recommended group before the "more" button.
         *
         * Three, because the point of the Recommended view is to see that the
         * groups exist and scroll past the ones you do not want. At six -- what
         * this fetched before -- four groups was a twenty-eight row list, and
         * reaching "Recently Watched" meant paging through everything above it.
         * The whole group is one press away from the button underneath it.
         */
        private const val GROUP_PREVIEW = 3

        /**
         * How much of each group we actually fetch.
         *
         * More than is shown, on purpose: the difference is what tells us
         * whether "more" is worth offering at all. Fetching exactly three would
         * mean either always drawing the button or making a second request to
         * find out.
         */
        private const val GROUP_FETCH = 12

        /** Index of each tab in the strip, left to right. */
        private const val TAB_RECOMMENDED = 0
        private const val TAB_LIBRARY = 1
        private const val TAB_CATEGORIES = 2

        fun intent(
            ctx: Context,
            mode: String,
            key: String,
            title: String,
            sectionType: String = "",
            path: String = "",
            group: String = "",
        ): Intent = Intent(ctx, BrowseActivity::class.java)
            .putExtra(EXTRA_MODE, mode)
            .putExtra(EXTRA_KEY, key)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SECTION_TYPE, sectionType)
            .putExtra(EXTRA_PATH, path)
            .putExtra(EXTRA_GROUP, group)
    }

    private lateinit var list: RowList
    private lateinit var rail: LinearLayout
    private lateinit var tabBar: TabStrip
    private lateinit var tabRule: View
    private lateinit var mode: String
    private lateinit var key: String
    private lateinit var title: String
    private lateinit var sectionType: String

    /** Non-empty for a filtered list, e.g. one genre. Set by [MODE_SECTION]. */
    private lateinit var path: String

    /** Which Recommended group [MODE_GROUP] is showing. */
    private lateinit var group: String

    private val uri get() = store.serverUri
    private val token get() = store.serverToken

    /** What the "more" button under a Recommended group carries. */
    private data class More(val id: String, val title: String)

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
        path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        group = intent.getStringExtra(EXTRA_GROUP).orEmpty()

        val body = layoutInflater.inflate(R.layout.browse_body, null)
        setBody(body)
        rail = body.findViewById(R.id.letter_rail)
        tabBar = body.findViewById(R.id.browse_tabs)
        tabRule = body.findViewById(R.id.browse_tabs_rule)
        tabBar.setTabs(
            listOf(
                getString(R.string.tab_recommended),
                getString(R.string.tab_library),
                getString(R.string.tab_categories),
            )
        )
        list = RowList(this)
        body.findViewById<FrameLayout>(R.id.browse_list_holder).addView(list)

        setHeader(title)
        setSoftKeys(left = getString(R.string.soft_home), right = getString(R.string.soft_options))
        setUpTabs()

        list.onChoose = { _, row -> choose(row.payload) }
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
                MODE_CATEGORIES -> loadCategories(u, t)
                MODE_SECTION -> loadSectionPage(u, t, keepSelection)
                MODE_GROUP -> flat(loadGroup(u, t), keepSelection)
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
     * grouping is expressed vertically, with captions. Each group shows
     * [GROUP_PREVIEW] entries and then a thin button to the rest of it, which is
     * what keeps four groups inside a list you can scroll rather than one you
     * have to page.
     *
     * Groups are fetched sequentially rather than concurrently. Four small
     * requests on a home LAN are fast enough, and a sequential failure is
     * legible where four interleaved ones are not.
     */
    private suspend fun loadRecommended(u: String, t: String) {
        val deck = PlexLibrary.onDeckInSection(u, t, key, size = GROUP_FETCH)
        // "Recently released" is only meaningful for television: it is ordered by
        // air date, which a film library does not have in any useful sense.
        val released =
            if (sectionType == "show") PlexLibrary.recentlyReleased(u, t, key, size = GROUP_FETCH)
            else emptyList()
        val added = PlexLibrary.recentlyAddedInSection(u, t, key, size = GROUP_FETCH)
        val viewed = PlexLibrary.recentlyViewed(u, t, key, size = GROUP_FETCH)

        val rows = buildList {
            fun group(caption: String, id: String, items: List<PlexItem>) {
                if (items.isEmpty()) return
                add(RowList.Row(title = caption.uppercase(), isHeader = true))
                items.take(GROUP_PREVIEW).forEach { add(it.toRow()) }
                if (items.size > GROUP_PREVIEW) {
                    add(
                        RowList.Row(
                            title = "»  more",
                            isThin = true,
                            payload = More(id, caption),
                        )
                    )
                }
            }
            group("Continue Watching", GROUP_ONDECK, deck)
            group("Recently Released", GROUP_RELEASED, released)
            group("Recently Added", GROUP_ADDED, added)
            group("Recently Watched", GROUP_VIEWED, viewed)
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

    /** One Recommended group, in full. */
    private suspend fun loadGroup(u: String, t: String): List<PlexItem> = when (group) {
        GROUP_ONDECK -> PlexLibrary.onDeckInSection(u, t, key, size = PlexLibrary.PAGE)
        GROUP_RELEASED -> PlexLibrary.recentlyReleased(u, t, key, size = PlexLibrary.PAGE)
        GROUP_VIEWED -> PlexLibrary.recentlyViewed(u, t, key, size = PlexLibrary.PAGE)
        else -> PlexLibrary.recentlyAddedInSection(u, t, key, size = PlexLibrary.PAGE)
    }

    /**
     * The genres in a library.
     *
     * Only genres, of the facets Plex offers. On a screen that shows seven rows,
     * a list of facets to pick a facet from is a level of navigation that buys
     * nothing -- and genre is the one people actually browse by.
     */
    private suspend fun loadCategories(u: String, t: String) {
        val cats = PlexLibrary.genres(u, t, key)
        if (cats.isEmpty()) {
            showMessage("No categories in this library.")
            list.submit(emptyList())
            return
        }
        list.submit(
            cats.map { c ->
                RowList.Row(
                    title = c.title,
                    trailing = if (c.count > 0) c.count.toString() else "",
                    payload = c,
                )
            }
        )
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
        // A filtered list -- one genre -- has no letter rail. The firstCharacter
        // endpoint answers for a whole section only, so its letters would not
        // match what is on screen and jumping to one would silently drop the
        // filter.
        if (path.isEmpty()) loadLetters(u, t)
    }

    private suspend fun fetchPage(u: String, t: String, offset: Int): PlexLibrary.Page {
        val l = letter
        return when {
            path.isNotEmpty() -> PlexLibrary.pathItems(u, t, path, offset)
            l == null -> PlexLibrary.sectionItems(u, t, key, offset)
            else -> PlexLibrary.byFirstCharacter(u, t, key, l, offset)
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

    // ---- the view tabs -----------------------------------------------------

    /**
     * Which tab this screen is under, or null where the strip does not belong.
     *
     * Continue Watching, Recently Added, a show and a season are not views *of*
     * a library, so they get no strip -- three tabs above a season's episodes
     * would offer to navigate somewhere the screen has no relationship to. A
     * "more" list is a drill-down from Recommended and is left alone for the
     * same reason; the back arrow is the way out of it.
     */
    private fun activeTab(): Int? = when {
        key.isEmpty() -> null
        mode == MODE_RECOMMENDED -> TAB_RECOMMENDED
        mode == MODE_CATEGORIES -> TAB_CATEGORIES
        // A genre browse is still "in" Categories, and saying so is the point of
        // the strip: without it, a filtered list is indistinguishable from the
        // whole library.
        mode == MODE_SECTION -> if (path.isEmpty()) TAB_LIBRARY else TAB_CATEGORIES
        else -> null
    }

    private fun setUpTabs() {
        val active = activeTab()
        val show = active != null
        tabBar.visibility = if (show) View.VISIBLE else View.GONE
        tabRule.visibility = if (show) View.VISIBLE else View.GONE
        if (active != null) tabBar.setActive(active)
    }

    /** Returns true when the strip took the cursor, so the caller can consume the key. */
    private fun focusTabs(on: Boolean): Boolean {
        if (activeTab() == null) return false
        tabBar.setFocused(on)
        list.parked = on
        return true
    }

    /**
     * `1` is the first tab, `2` the second, and so on.
     *
     * Ignored rather than clamped where the digit names no tab: pressing 7 on a
     * three-tab screen must not switch to Categories, because the user was
     * plainly not asking for Categories. Ignored too where the screen has no
     * strip at all -- a season's episode list has nothing for a digit to mean.
     */
    private fun chooseTabByDigit(keyCode: Int): Boolean {
        if (activeTab() == null) return false
        val wanted = KeyMap.digitOf(keyCode) - 1
        if (wanted !in 0 until tabBar.count) return false
        switchTab(wanted)
        return true
    }

    /** True when [tab] is already the screen we are on. */
    private fun isShowing(tab: Int): Boolean = when (tab) {
        TAB_RECOMMENDED -> mode == MODE_RECOMMENDED
        TAB_LIBRARY -> mode == MODE_SECTION && path.isEmpty()
        else -> mode == MODE_CATEGORIES
    }

    /**
     * Switch views.
     *
     * `finish()` afterwards, because the strip is a view switcher and not
     * navigation: leaving the old view on the stack would make the back arrow
     * walk sideways through views you have already looked at instead of up a
     * level, and four presses later you would still be inside the library.
     *
     * The one place that is deliberately *not* a no-op is pressing Categories
     * while inside a single genre -- that is a different screen, and it is the
     * quickest way back to the genre list.
     */
    private fun switchTab(tab: Int) {
        if (isShowing(tab)) {
            focusTabs(false)
            return
        }
        val next = when (tab) {
            TAB_RECOMMENDED -> intent(this, MODE_RECOMMENDED, key, title, sectionType)
            TAB_LIBRARY -> intent(this, MODE_SECTION, key, title, sectionType)
            else -> intent(this, MODE_CATEGORIES, key, title, sectionType)
        }
        startActivity(next)
        finish()
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
        list.parked = on
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
     * Seasons get the same treatment, and it cuts both ways. Recently Added
     * returns *seasons*, whose title is "Season 10" -- which on its own names
     * nothing at all, so the show goes on the first line there. But in the list
     * of a show's own seasons the show name is already in the header, and
     * putting it on every row gives you a screen that says "Queen of Tears" six
     * times with the one thing that distinguishes the rows in small print
     * underneath. There, the season is the title and the episode count is the
     * subtitle.
     */
    private fun PlexItem.toRow(): RowList.Row {
        val seasonEpisode = if (parentIndex >= 0 && index >= 0) "S$parentIndex · E$index" else ""
        val inChildren = mode == MODE_CHILDREN

        val rowTitle: String
        val rowSubtitle: String
        when {
            type == "episode" && !inChildren && grandparentTitle.isNotEmpty() -> {
                rowTitle = grandparentTitle
                rowSubtitle = listOf(seasonEpisode, title)
                    .filter { it.isNotEmpty() }
                    .joinToString("  ")
            }
            type == "episode" -> {
                rowTitle = title
                rowSubtitle = seasonEpisode
            }
            type == "season" && !inChildren && parentTitle.isNotEmpty() -> {
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

    private fun choose(payload: Any?) {
        when (payload) {
            is PlexItem -> open(payload)
            is More -> startActivity(
                intent(this, MODE_GROUP, key, payload.title, sectionType, group = payload.id)
            )
            is PlexLibrary.Category -> startActivity(
                intent(this, MODE_SECTION, key, payload.title, sectionType, path = payload.path)
            )
        }
    }

    /**
     * Choosing a row.
     *
     * A playable item now lands on its details page rather than starting a
     * transcode, because the description, the source resolution and the
     * subtitle and audio tracks are not reachable from anywhere else -- and
     * because the two decisions people most often want (subtitles on, and not
     * at full bitrate on a hotspot) are ones they think of at exactly this
     * moment, not three levels deep in Settings.
     *
     * `Store.showDetails` puts the old behaviour back for anyone who only ever
     * resumes the same show, and the Options menu keeps its own Play and Resume
     * entries either way -- this is the default path, not the only one.
     */
    private fun open(item: PlexItem) {
        when {
            item.isPlayable && store.showDetails ->
                startActivity(DetailActivity.intent(this, item.ratingKey, item.title))
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
        // startPlayback rather than startActivity: it is the one place that
        // knows whether this would come over somebody's mobile data, and it
        // asks before the transcode is requested rather than after.
        startPlayback(
            PlayerActivity.intent(
                this,
                ratingKey = item.ratingKey,
                title = item.title,
                subtitle = item.subtitle(),
                startMs = if (resume) item.viewOffsetMs else 0L,
            )
        )
    }

    /**
     * Play something at random.
     *
     * The failure path matters as much as the success one. This used to end in
     * `randomOrNull()?.let { play(it) }`, so a show whose episodes came back
     * empty -- an unmatched folder, a season with no files, a request that
     * failed and returned nothing -- did precisely nothing, with no message. An
     * option that silently does nothing is indistinguishable from a broken one,
     * which is exactly how it was reported.
     */
    private fun shuffle(pick: suspend () -> PlexItem?) {
        lifecycleScope.launch {
            setBusy(true)
            val item = pick()
            setBusy(false)
            if (item == null) showTransientMessage("Nothing to shuffle here.")
            else play(item, resume = false)
        }
    }

    // ---- options -----------------------------------------------------------

    override fun optionsHeading(): String = list.selectedRow()?.title.orEmpty()

    override fun optionsFor(): List<Option> {
        val u = uri ?: return emptyList()
        val t = token ?: return emptyList()
        val item = list.selectedRow()?.payload as? PlexItem

        return buildList {
            // View switching first: it applies to the screen rather than to a
            // row. The tab strip does this too, and both are kept -- the strip
            // is what tells you where you are, the menu is where someone who has
            // not noticed the strip will look for it.
            if (activeTab() != null) {
                if (!isShowing(TAB_RECOMMENDED)) {
                    add(Option("Recommended") { switchTab(TAB_RECOMMENDED) })
                }
                if (!isShowing(TAB_LIBRARY)) {
                    add(Option("All titles (A-Z)") { switchTab(TAB_LIBRARY) })
                }
                if (!isShowing(TAB_CATEGORIES)) {
                    add(Option("Categories") { switchTab(TAB_CATEGORIES) })
                }
                if (letter != null) {
                    add(Option("All letters") { letter = null; load() })
                }
                // Shuffling a whole library, from inside it. Offered on the
                // unfiltered views only: on one genre it would be a shuffle of
                // everything, which is not what the screen is showing.
                //
                // Named after the library, not just "Shuffle", because the
                // focused row may offer a shuffle of its own -- and two entries
                // reading "Shuffle" in one menu, one meaning this show and one
                // meaning all six hundred of them, is a menu that cannot be
                // used without trying it.
                if (path.isEmpty()) {
                    add(Option("Shuffle $title") {
                        shuffle { PlexLibrary.randomInSection(u, t, key, sectionType) }
                    })
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
                // Only offered when OK does something else. With the details
                // page on, the row itself is the way there, and a menu entry
                // duplicating the highlighted row's own action is noise.
                if (!store.showDetails) {
                    add(Option("Details") {
                        startActivity(DetailActivity.intent(this@BrowseActivity, item.ratingKey, item.title))
                    })
                }
            }
            if (item.isContainer) {
                add(Option("Details") {
                    startActivity(DetailActivity.intent(this@BrowseActivity, item.ratingKey, item.title))
                })
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
                        if (next == null) showTransientMessage("Nothing to play here.")
                        else play(next, resume = next.inProgress)
                    }
                })
                val what = when (item.type) {
                    "season" -> "this season"
                    "collection" -> "this collection"
                    else -> "this show"
                }
                add(Option("Shuffle $what") {
                    // filter, not a bare random: allLeaves on a collection can
                    // return the shows inside it rather than episodes, and
                    // handing a show's ratingKey to the player asks the server
                    // to transcode something that has no media.
                    shuffle {
                        PlexLibrary.allLeaves(u, t, item.ratingKey)
                            .filter { it.isPlayable }
                            .randomOrNull()
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

    override fun onHeaderFocusChanged(on: Boolean) {
        list.parked = on
    }

    override fun onAction(action: Action, keyCode: Int): Boolean {
        if (tabBar.focused) {
            return when (action) {
                Action.LEFT -> { tabBar.move(-1); true }
                Action.RIGHT -> { tabBar.move(+1); true }
                Action.SELECT -> { switchTab(tabBar.cursor); true }
                Action.DOWN, Action.BACK -> { focusTabs(false); true }
                // Up used to be swallowed here, because the strip was the top of
                // the screen and there was nowhere further to go. There is now:
                // the header, where OK goes up a level. So the chain runs list,
                // tabs, title, and every step of it is one press of the same key.
                Action.UP -> {
                    if (focusHeader(true)) focusTabs(false)
                    true
                }
                Action.DIGIT -> chooseTabByDigit(keyCode)
                else -> false
            }
        }

        if (railFocused) {
            return when (action) {
                // Off the top of the alphabet is the tab strip. The rail is the
                // one place in the app where the cursor can be a long way from
                // the top of a several-hundred-row list, and this is the way
                // back up that does not involve holding a key: Z to A, then one
                // more press, then Recommended.
                Action.UP -> {
                    if (railIndex == 0) {
                        focusRail(false)
                        if (!focusTabs(true)) focusHeader(true)
                    } else {
                        railIndex -= 1
                        paintRail()
                    }
                    true
                }
                Action.DOWN -> {
                    railIndex = (railIndex + 1).coerceAtMost(letters.size - 1); paintRail(); true
                }
                Action.SELECT -> { jumpToLetter(); true }
                Action.LEFT, Action.BACK -> { focusRail(false); true }
                Action.DIGIT -> { focusRail(false); chooseTabByDigit(keyCode) }
                else -> false
            }
        }

        return when (action) {
            // Up off the top row steps into the tab strip, and off the top of
            // that into the header. Where there is no strip -- a season, a
            // "more" list -- it goes straight to the header.
            Action.UP -> list.move(-1) || focusTabs(true) || focusHeader(true)
            Action.DOWN -> list.move(+1)
            // Right steps into the letter rail where there is one, and pages
            // where there is not.
            Action.RIGHT -> if (letters.isNotEmpty()) { focusRail(true); true } else list.move(+PAGE_JUMP)
            // Left is up a level, on every screen in the app but the player.
            // It used to page backwards, which star already does, and paging
            // was the less valuable of the two: a back arrow is one small
            // physical key on a phone that may well outlive it.
            Action.LEFT -> { goUp(); true }
            // Star and hash page, always. They arrive on this handset, they have
            // no other job, and paging has to stay reachable once left is spent
            // on going back and right on the rail.
            Action.STAR -> list.move(-PAGE_JUMP)
            Action.POUND -> list.move(+PAGE_JUMP)
            // A digit is the tab of that number, from anywhere in the list. The
            // point is the six-hundred-title A-Z: getting back to Recommended
            // was a held key and a wait, and it is now one press.
            Action.DIGIT -> chooseTabByDigit(keyCode)
            Action.SELECT -> if (list.rows.isEmpty()) {
                // An empty Recommended view offers the A-Z instead.
                if (mode == MODE_RECOMMENDED) switchTab(TAB_LIBRARY) else load()
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
        // position. The grouped views are rebuilt wholesale; a paged A-Z is left
        // alone, because re-fetching would throw away everything scrolled so far.
        if ((mode == MODE_RECOMMENDED || mode == MODE_GROUP) && list.rows.isNotEmpty()) load()
    }
}
