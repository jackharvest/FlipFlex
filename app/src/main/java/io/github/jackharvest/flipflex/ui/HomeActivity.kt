package io.github.jackharvest.flipflex.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import io.github.jackharvest.flipflex.R
import io.github.jackharvest.flipflex.input.Action
import io.github.jackharvest.flipflex.plex.PlexItem
import io.github.jackharvest.flipflex.plex.PlexLibrary
import io.github.jackharvest.flipflex.plex.PlexServers
import kotlinx.coroutines.launch

/**
 * The start screen, and what the Home softkey returns to.
 *
 * Continue Watching sits at the top and is not a submenu: on a phone that gets
 * opened for four minutes at a time, the shortest path from lid-open to
 * playing the thing you were already watching is the feature. Everything below
 * it is browsing, which is the rarer case.
 */
class HomeActivity : FlipActivity() {

    private lateinit var list: RowList

    /** What a home row does when chosen. */
    private sealed interface Dest {
        data object OnDeck : Dest
        data object Recent : Dest
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
            // Both fetches are needed before the menu can be drawn, because the
            // Continue Watching row shows its own count and the libraries are
            // one row each. Sequential rather than parallel: this SoC on a
            // 1 Mbps uplink gains nothing from two in-flight requests, and the
            // sequential version is the one whose failure is easy to read.
            val deck = PlexLibrary.onDeck(uri, token)
            val sections = PlexLibrary.sections(uri, token)
            setBusy(false)

            if (deck.isEmpty() && sections.isEmpty()) {
                showMessage(getString(R.string.msg_offline))
                return@launch
            }

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
                sections.forEach { s ->
                    add(
                        RowList.Row(
                            title = s.title,
                            subtitle = if (s.type == "show") "TV Shows" else "Movies",
                            payload = Dest.Section(s),
                        )
                    )
                }
                add(RowList.Row(title = getString(R.string.home_recent), payload = Dest.Recent))
                add(RowList.Row(title = getString(R.string.home_settings), payload = Dest.Settings))
            }
            list.submit(rows)
        }
    }

    private fun open(dest: Dest) {
        when (dest) {
            Dest.OnDeck -> startActivity(
                BrowseActivity.intent(this, BrowseActivity.MODE_ONDECK, "", getString(R.string.home_continue))
            )
            Dest.Recent -> startActivity(
                BrowseActivity.intent(this, BrowseActivity.MODE_RECENT, "", getString(R.string.home_recent))
            )
            is Dest.Section -> startActivity(
                BrowseActivity.intent(this, BrowseActivity.MODE_SECTION, dest.item.sectionKey, dest.item.title)
            )
            Dest.Settings -> {
                // No settings screen yet. Sign-out is the one setting the proof
                // of concept genuinely needs, because re-linking is the only way
                // to recover from a wrong account or a stale token.
                store.signOut()
                startActivity(Intent(this, LinkActivity::class.java))
                finish()
            }
        }
    }

    override fun optionsHeading(): String = store.serverName ?: getString(R.string.app_name)

    override fun optionsFor(): List<Option> = listOf(
        Option("Refresh") { load() },
        Option("Change server") {
            lifecycleScope.launch {
                setBusy(true)
                // Force a fresh pick by clearing the pinned server id, so the
                // ranking runs over every server on the account rather than
                // re-choosing the one we already had.
                store.serverClientId = null
                val token = store.token
                val chosen = token?.let { PlexServers.pick(it) }
                setBusy(false)
                if (chosen != null) {
                    store.serverUri = chosen.uri
                    store.serverToken = chosen.token
                    store.serverName = chosen.name
                    store.serverClientId = chosen.serverId
                    setHeader(chosen.name, showChevron = false)
                    load()
                } else {
                    showMessage(getString(R.string.msg_no_server))
                }
            }
        },
        Option("Sign out") { open(Dest.Settings) },
    )

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
        // Coming back from an episode should show the updated resume position
        // in Continue Watching. Reloading on every resume is affordable because
        // the home fetch is two small requests.
        if (list.rows.isNotEmpty()) load()
    }

    override fun goHome() {
        // Already here.
    }
}
