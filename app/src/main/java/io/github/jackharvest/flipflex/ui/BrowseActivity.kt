package io.github.jackharvest.flipflex.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import io.github.jackharvest.flipflex.R
import io.github.jackharvest.flipflex.input.Action
import io.github.jackharvest.flipflex.plex.PlexItem
import io.github.jackharvest.flipflex.plex.PlexLibrary
import kotlinx.coroutines.launch

/**
 * Every list of Plex content, in one screen.
 *
 * Continue Watching, a library's A-Z, a show's seasons and a season's episodes
 * differ only in which endpoint fills them -- they render identically and are
 * navigated identically. One activity with a mode extra rather than four
 * near-copies, because on a device this constrained the interesting differences
 * are all in the Options menu, not in the list.
 */
class BrowseActivity : FlipActivity() {

    companion object {
        const val MODE_ONDECK = "ondeck"
        const val MODE_RECENT = "recent"
        const val MODE_SECTION = "section"
        const val MODE_CHILDREN = "children"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TITLE = "title"

        /** Roughly a screenful: about 7 rows fit the 270dp content area. */
        private const val PAGE_JUMP = 7

        fun intent(ctx: Context, mode: String, key: String, title: String): Intent =
            Intent(ctx, BrowseActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_TITLE, title)
    }

    private lateinit var list: RowList
    private lateinit var mode: String
    private lateinit var key: String
    private lateinit var title: String

    private val uri get() = store.serverUri
    private val token get() = store.serverToken

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ONDECK
        key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        list = RowList(this)
        setBody(list)
        setHeader(title)
        setSoftKeys(left = getString(R.string.soft_home), right = getString(R.string.soft_options))

        list.onChoose = { _, row -> open(row.payload as PlexItem) }
        load()
    }

    private fun load(keepSelection: Boolean = false) {
        val u = uri
        val t = token
        if (u == null || t == null) {
            showMessage(getString(R.string.msg_no_server))
            return
        }
        showMessage(null)
        setBusy(true)
        lifecycleScope.launch {
            val items = when (mode) {
                MODE_ONDECK -> PlexLibrary.onDeck(u, t)
                MODE_RECENT -> PlexLibrary.recentlyAdded(u, t)
                MODE_SECTION -> PlexLibrary.sectionItems(u, t, key)
                else -> PlexLibrary.children(u, t, key)
            }
            setBusy(false)
            if (items.isEmpty()) {
                showMessage(getString(R.string.msg_empty))
                list.submit(emptyList())
                return@launch
            }
            list.submit(items.map { it.toRow() }, keepSelection)
        }
    }

    /**
     * An episode needs a different row shape depending on where it is listed,
     * and getting this wrong wastes the only two lines a row has.
     *
     * On Continue Watching the show is what identifies the row -- an episode
     * title alone is useless there, because "Episode 14" appears in every show
     * on the server. Inside a season the show name is the one thing every row
     * already shares, so repeating it costs width and tells you nothing.
     *
     * The first version put the show name in *both* lines on Continue Watching
     * ("Queen of Tears" over "S1 · E14  Queen of Tears"), which is where this
     * split came from.
     */
    private fun PlexItem.toRow(): RowList.Row {
        val seasonEpisode = if (parentIndex >= 0 && index >= 0) "S$parentIndex · E$index" else ""
        val crossLibrary = mode != MODE_CHILDREN

        val rowTitle: String
        val rowSubtitle: String
        when {
            type == "episode" && crossLibrary && grandparentTitle.isNotEmpty() -> {
                rowTitle = grandparentTitle
                rowSubtitle = listOf(seasonEpisode, title)
                    .filter { it.isNotEmpty() }
                    .joinToString("  ")
            }
            type == "episode" -> {
                rowTitle = title
                rowSubtitle = seasonEpisode
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
                intent(this, MODE_CHILDREN, item.ratingKey, item.title)
            )
        }
    }

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

    /**
     * The right softkey's menu: this is Plex's three-dot menu, and it is the
     * whole reason neither softkey is spent on Back.
     */
    override fun optionsFor(): List<Option> {
        val item = list.selectedRow()?.payload as? PlexItem ?: return emptyList()
        val u = uri ?: return emptyList()
        val t = token ?: return emptyList()

        return buildList {
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
                        // "Next up" is the first unwatched episode, or the one
                        // that was left part-way through -- the same rule Plex
                        // itself uses, so the two agree about where you are.
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
            // run is otherwise a trip back to Home and down through a library --
            // five presses to reach something that is conceptually one away.
            if (item.type == "episode" && item.parentRatingKey.isNotEmpty()) {
                add(Option("Go to season") {
                    startActivity(
                        intent(
                            this@BrowseActivity, MODE_CHILDREN,
                            item.parentRatingKey, item.parentTitle.ifEmpty { "Season" },
                        )
                    )
                })
            }
            if (item.type == "episode" && item.grandparentRatingKey.isNotEmpty()) {
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

    override fun onAction(action: Action, keyCode: Int): Boolean = when (action) {
        Action.UP -> list.move(-1)
        Action.DOWN -> list.move(+1)
        // Left and right page through a long library a screenful at a time. On a
        // 600-title list, one row per press is not navigation.
        Action.LEFT -> list.move(-PAGE_JUMP)
        Action.RIGHT -> list.move(+PAGE_JUMP)
        Action.SELECT -> if (list.rows.isEmpty()) { load(); true } else list.choose()
        else -> false
    }

    override fun onResume() {
        super.onResume()
        // Returning from the player: the row we just watched now has a new
        // resume position, and keepSelection means the cursor does not jump
        // back to the top of a long list.
        if (list.rows.isNotEmpty()) load(keepSelection = true)
    }
}
