package com.github.jackharvest.flipflex.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.dl.DownloadService
import com.github.jackharvest.flipflex.dl.Downloads
import com.github.jackharvest.flipflex.input.Action
import com.github.jackharvest.flipflex.plex.PlexItem
import com.github.jackharvest.flipflex.plex.PlexLibrary
import kotlinx.coroutines.launch

/**
 * The start screen, and what the Home softkey returns to.
 *
 * Continue Watching sits at the top and is not a submenu: on a phone that gets
 * opened for four minutes at a time, the shortest path from lid-open to playing
 * the thing you were already watching is the feature. Everything below it is
 * browsing, which is the rarer case.
 */
class HomeActivity : FlipActivity() {

    private lateinit var list: RowList

    /** Every section on the server, including hidden ones, for the options menu. */
    private var allSections: List<PlexItem> = emptyList()

    /** What a home row does when chosen. */
    private sealed interface Dest {
        data object OnDeck : Dest
        data object Recent : Dest
        data object Downloads : Dest
        data object Settings : Dest
        data class Section(val item: PlexItem) : Dest
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = RowList(this)
        setBody(list)

        setHeader(store.serverName ?: getString(R.string.app_name), showChevron = false)
        // Home is already home. Labelling the left key "Home" here would be a
        // key that visibly does nothing, so it stays blank.
        setSoftKeys(left = null, right = getString(R.string.soft_options))

        list.onChoose = { _, row -> open(row.payload as Dest) }
        load()
    }

    private fun load() {
        val uri = store.serverUri
        val token = store.serverToken
        if (uri == null || token == null) {
            showMessage(getString(R.string.msg_no_server))
            return
        }

        showMessage(null)
        setBusy(true)
        lifecycleScope.launch {
            val deck = PlexLibrary.onDeck(uri, token)
            val sections = PlexLibrary.sections(uri, token)
            setBusy(false)

            if (deck.isEmpty() && sections.isEmpty()) {
                offline()
                return@launch
            }

            allSections = sections
            render(deck, sections)
        }
    }

    /**
     * No server -- but the phone may still be full of things to watch.
     *
     * This is the case the whole download feature exists for, so it must not
     * end at "Cannot reach Plex". If anything has been downloaded, the home
     * screen becomes a shorter home screen rather than an error: Downloads,
     * Settings, and a line saying why the libraries are missing.
     */
    private fun offline() {
        if (Downloads.countDone(this) == 0) {
            showMessage(getString(R.string.msg_offline))
            return
        }
        showMessage(null)
        list.submit(
            listOf(
                RowList.Row(
                    title = getString(R.string.home_downloads),
                    subtitle = getString(R.string.home_offline_note),
                    trailing = Downloads.countDone(this).toString(),
                    payload = Dest.Downloads,
                ),
                RowList.Row(title = getString(R.string.home_settings), payload = Dest.Settings),
            ),
            keepSelection = true,
        )
    }

    private fun render(deck: List<PlexItem>, sections: List<PlexItem>) {
        val hidden = store.hiddenSections
        val rows = buildList {
            if (deck.isNotEmpty()) {
                add(
                    RowList.Row(
                        title = getString(R.string.home_continue),
                        trailing = deck.size.toString(),
                        payload = Dest.OnDeck,
                    )
                )
            }
            // Hidden libraries are dropped here rather than being greyed out.
            // The whole point of hiding the 4K library on a 240x320 screen is to
            // stop it taking a row, so leaving a disabled row would defeat it.
            sections.filter { it.sectionKey !in hidden }.forEach { s ->
                add(
                    RowList.Row(
                        title = s.title,
                        subtitle = if (s.type == "show") "TV Shows" else "Movies",
                        payload = Dest.Section(s),
                    )
                )
            }
            add(RowList.Row(title = getString(R.string.home_recent), payload = Dest.Recent))
            // Downloads sits with the libraries because that is what it is: a
            // library, browsed the same way, that happens to live on the phone.
            // Hidden when it is empty rather than shown as an empty folder --
            // a row that always says "0" is a row that trains people not to
            // look at it.
            val saved = Downloads.count(this@HomeActivity)
            if (saved > 0) {
                add(
                    RowList.Row(
                        title = getString(R.string.home_downloads),
                        subtitle = Downloads.humanBytes(Downloads.bytesOnDisk(this@HomeActivity)),
                        trailing = saved.toString(),
                        payload = Dest.Downloads,
                    )
                )
            }
            add(RowList.Row(title = getString(R.string.home_settings), payload = Dest.Settings))
        }
        list.submit(rows, keepSelection = true)
    }

    private fun open(dest: Dest) {
        when (dest) {
            Dest.OnDeck -> startActivity(
                BrowseActivity.intent(
                    this, BrowseActivity.MODE_ONDECK, "", getString(R.string.home_continue),
                )
            )
            Dest.Recent -> startActivity(
                BrowseActivity.intent(
                    this, BrowseActivity.MODE_RECENT, "", getString(R.string.home_recent),
                )
            )
            // A library opens on its Recommended view. The A-Z is one press away
            // from there, via Options.
            is Dest.Section -> startActivity(
                BrowseActivity.intent(
                    this, BrowseActivity.MODE_RECOMMENDED, dest.item.sectionKey, dest.item.title,
                    sectionType = dest.item.type,
                )
            )
            Dest.Downloads -> startActivity(DownloadsActivity.intent(this))
            Dest.Settings -> startActivity(SettingsActivity.intent(this))
        }
    }

    /**
     * Play one thing at random out of a whole library.
     *
     * Works the same for both kinds of library, which is the point:
     * [PlexLibrary.randomInSection] enumerates *episodes* for television, so
     * this is a random episode of a random show rather than a random show you
     * would then have to pick an episode of. On a phone that gets opened for
     * four minutes at a time, that distinction is the whole feature.
     */
    private fun shuffle(section: PlexItem) {
        val u = store.serverUri ?: return
        val t = store.serverToken ?: return
        lifecycleScope.launch {
            setBusy(true)
            val item = PlexLibrary.randomInSection(u, t, section.sectionKey, section.type)
            setBusy(false)
            if (item == null) {
                showTransientMessage("Nothing to shuffle in\n${section.title}.")
                return@launch
            }
            startActivity(
                PlayerActivity.intent(
                    this@HomeActivity,
                    ratingKey = item.ratingKey,
                    title = item.title,
                    subtitle = item.subtitle(),
                    startMs = 0L,
                )
            )
        }
    }

    override fun optionsHeading(): String = list.selectedRow()?.title.orEmpty()

    override fun optionsFor(): List<Option> = buildList {
        val dest = list.selectedRow()?.payload

        // Both of these are offered on the library that is focused, which is
        // what makes them discoverable -- a "manage libraries" screen buried in
        // Settings is not where anyone looks when a library is annoying them
        // right now, and a shuffle you have to open a library to reach is one
        // press worse than it needs to be.
        if (dest is Dest.Section) {
            add(Option("Shuffle ${dest.item.title}") { shuffle(dest.item) })
            add(
                Option("Hide ${dest.item.title}") {
                    store.toggleSectionHidden(dest.item.sectionKey)
                    load()
                }
            )
        }
        if (store.hiddenSections.isNotEmpty()) {
            add(Option("Show hidden libraries") {
                store.hiddenSections = emptySet()
                load()
            })
        }

        add(Option("Settings") { startActivity(SettingsActivity.intent(this@HomeActivity)) })
        add(Option("Switch profile") {
            startActivity(SettingsActivity.intent(this@HomeActivity, SettingsActivity.MODE_PROFILE))
        })
        add(Option("Change server") {
            startActivity(SettingsActivity.intent(this@HomeActivity, SettingsActivity.MODE_SERVER))
        })
        add(Option("Refresh") { load() })
    }

    override fun onAction(action: Action, keyCode: Int): Boolean = when (action) {
        Action.UP -> list.move(-1)
        Action.DOWN -> list.move(+1)
        // With no rows the screen is showing an error, and OK is the retry --
        // it is the only key on the error state, so it has to mean "try again"
        // there and "open this" everywhere else.
        Action.SELECT -> if (list.rows.isEmpty()) { load(); true } else list.choose()
        else -> false
    }

    override fun onResume() {
        super.onResume()
        // Coming back from an episode should show the updated resume position in
        // Continue Watching, and coming back from Settings should reflect a
        // changed server, profile or hidden set.
        setHeader(store.serverName ?: getString(R.string.app_name), showChevron = false)
        if (list.rows.isNotEmpty()) load()
    }

    override fun goHome() {
        // Already here.
    }
}
