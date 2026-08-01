package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.dl.Downloads
import com.github.jackharvest.flipflex.input.Action
import com.github.jackharvest.flipflex.input.KeyMap
import com.github.jackharvest.flipflex.plex.PlexItem
import com.github.jackharvest.flipflex.plex.PlexLibrary
import com.github.jackharvest.flipflex.plex.PlexProfiles
import com.github.jackharvest.flipflex.plex.PlexServers
import com.github.jackharvest.flipflex.plex.Quality
import com.github.jackharvest.flipflex.store.NetPolicy
import kotlinx.coroutines.launch

/**
 * Settings, and the several screens reachable from it.
 *
 * This replaces the placeholder that made "Settings" on the home list an
 * immediate, unconfirmed sign-out -- choosing it wiped the token and dropped
 * the user back at plex.tv/link with no warning and no way to undo it. Sign-out
 * is still here, but it is one entry among several and it asks first.
 *
 * One activity with a mode extra rather than five near-identical list screens,
 * for the same reason BrowseActivity is: they render identically and are
 * navigated identically, and the differences are all in what fills the list.
 */
class SettingsActivity : FlipActivity() {

    companion object {
        const val MODE_ROOT = "root"
        const val MODE_PROFILE = "profile"
        const val MODE_SERVER = "server"
        const val MODE_LIBRARIES = "libraries"

        /** Index of each root tab in the strip, left to right. */
        private const val TAB_PLAYBACK = 0
        private const val TAB_DOWNLOADS = 1
        private const val TAB_ACCOUNT = 2
        private const val TAB_HELP = 3

        private const val EXTRA_MODE = "mode"

        /** Tip jar. Bottom of the last tab, and referenced nowhere else. */
        private const val COFFEE_URL = "https://buymeacoffee.com/jackharvest"

        fun intent(ctx: Context, mode: String = MODE_ROOT): Intent =
            Intent(ctx, SettingsActivity::class.java).putExtra(EXTRA_MODE, mode)
    }

    private lateinit var list: RowList
    private lateinit var mode: String

    /** The four groups, as tabs. Only [MODE_ROOT] has any. */
    private var tabs: TabStrip? = null
    private var tab = TAB_PLAYBACK

    /** Non-null while the PIN pad is up, which routes keys away from the list. */
    private var pinView: View? = null
    private var pinUser: PlexProfiles.User? = null
    private var pinDigits = StringBuilder()

    /** What a settings row does. Held in the row payload. */
    private data class Entry(val run: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ROOT
        list = RowList(this)

        // Only the root is tabbed. The sub-screens are one list each -- a
        // profile list, a server list -- and a strip above them would be three
        // tabs offering to leave a screen you arrived at one press ago.
        if (mode == MODE_ROOT) {
            val body = layoutInflater.inflate(R.layout.tabbed_body, null)
            setBody(body)
            body.findViewById<FrameLayout>(R.id.body_list_holder).addView(list)
            tabs = body.findViewById<TabStrip>(R.id.body_tabs).apply {
                setTabs(
                    listOf(
                        getString(R.string.settings_tab_playback),
                        getString(R.string.settings_tab_downloads),
                        getString(R.string.settings_tab_account),
                        getString(R.string.settings_tab_help),
                    )
                )
                setActive(tab)
            }
        } else {
            setBody(list)
        }

        setSoftKeys(left = getString(R.string.soft_home), right = null)
        list.onChoose = { _, row -> (row.payload as? Entry)?.run?.invoke() }
        load()
    }

    private fun load() {
        when (mode) {
            MODE_ROOT -> loadRoot()
            MODE_PROFILE -> loadProfiles()
            MODE_SERVER -> loadServers()
            MODE_LIBRARIES -> loadLibraries()
        }
    }

    // ---- root --------------------------------------------------------------

    /**
     * The settings for whichever tab is showing.
     *
     * ## Why this is four short lists and not one grouped one
     *
     * It was one list of fifteen rows with four captions in it, and the captions
     * were doing two jobs badly. As labels they were fine; as *rows* they were
     * the things the cursor had to skip over, and skipping one is what put the
     * amber bar off the bottom of the screen -- see [RowList.scrollToCursor] for
     * the mechanism. Even with that fixed, reaching "Sign out" was two pages of
     * scrolling past settings nobody was looking for.
     *
     * The same four groups as tabs cost one 13dp strip, which is paid once
     * rather than four times, and every list is now shorter than the screen. The
     * number keys reach a tab directly, which makes the deepest row in Settings
     * two presses from arriving instead of nine.
     *
     * ## Why the values are coloured
     *
     * The same four colours the details page uses, meaning the same four things:
     * blue is what the file already is, green is what will reach the screen,
     * violet is storage on the phone, rose is subtitles. Someone who has picked
     * a subtitle track on a details page has seen rose next to that decision;
     * finding the global default in the same colour is what says the two are the
     * same setting. Without it they are two unrelated rows that happen to share
     * a word.
     *
     * Every toggle acts in place and redraws, rather than opening a screen of
     * its own. A settings row whose value is on the row and changes when you
     * press it needs no explaining; a sub-screen for an on/off switch is two
     * extra presses and a back press.
     */
    private fun loadRoot() {
        setHeader("Settings")
        tabs?.setActive(tab)
        list.submit(
            when (tab) {
                TAB_PLAYBACK -> playbackRows()
                TAB_DOWNLOADS -> downloadRows()
                TAB_ACCOUNT -> accountRows()
                else -> helpRows()
            },
            keepSelection = true,
        )
    }

    private fun playbackRows(): List<RowList.Row> {
        val q = Quality.byId(store.quality)
        return buildList {
            add(
                RowList.Row(
                    title = "Quality",
                    subtitle = q.summary,
                    trailing = q.label,
                    accent = R.color.ff_badge_target,
                    payload = Entry { cycleQuality() },
                )
            )
            add(
                RowList.Row(
                    title = "Subtitles",
                    subtitle = "Burned in by the server",
                    trailing = if (store.subtitles) "On" else "Off",
                    accent = R.color.ff_badge_subs,
                    payload = Entry { store.subtitles = !store.subtitles; loadRoot() },
                )
            )
            add(
                RowList.Row(
                    title = "Subtitle size",
                    subtitle = "Plex's 100 is sized for a TV",
                    trailing = "${store.subtitleSize}%",
                    accent = R.color.ff_badge_subs,
                    payload = Entry { cycleSubtitleSize() },
                )
            )
            add(
                RowList.Row(
                    title = "Wi-Fi only",
                    subtitle = "Streaming on mobile data",
                    trailing = NetPolicy.label(store.streamNetwork),
                    accent = R.color.ff_badge_target,
                    payload = Entry {
                        store.streamNetwork = NetPolicy.next(store.streamNetwork)
                        loadRoot()
                    },
                )
            )
            // An experiment, not a feature, and labelled as one. The obvious
            // suspect for a stream that dies partway through a busy evening is
            // the server's transcoder, and this is the only way to take the
            // transcoder out of the path and see whether the failures stop. It
            // will not work on everything, which is why turning it on asks
            // first -- see [askDirectPlay]. Blue, because direct play is the
            // file arriving as it already is, and blue is what that colour
            // means everywhere else in this app.
            add(
                RowList.Row(
                    title = "Try direct play",
                    subtitle = "Skips the server's transcoder",
                    trailing = if (store.directPlay) "On" else "Off",
                    accent = R.color.ff_badge_source,
                    payload = Entry { toggleDirectPlay() },
                )
            )
            add(
                RowList.Row(
                    title = "Details before playing",
                    subtitle = "Description, tracks, download",
                    trailing = if (store.showDetails) "On" else "Off",
                    payload = Entry { store.showDetails = !store.showDetails; loadRoot() },
                )
            )
        }
    }

    private fun downloadRows(): List<RowList.Row> {
        val dq = Quality.byId(store.downloadQuality)
        return buildList {
            add(
                RowList.Row(
                    title = "Downloads",
                    subtitle = downloadSummary(),
                    trailing = Downloads.count(this@SettingsActivity).toString(),
                    accent = R.color.ff_badge_local,
                    payload = Entry { startActivity(DownloadsActivity.intent(this@SettingsActivity)) },
                )
            )
            add(
                RowList.Row(
                    title = "Download quality",
                    subtitle = dq.summary,
                    trailing = dq.label,
                    accent = R.color.ff_badge_local,
                    payload = Entry { cycleDownloadQuality() },
                )
            )
            add(
                RowList.Row(
                    title = "Wi-Fi only",
                    subtitle = "Downloading on mobile data",
                    trailing = NetPolicy.label(store.downloadNetwork),
                    accent = R.color.ff_badge_local,
                    payload = Entry {
                        store.downloadNetwork = NetPolicy.next(store.downloadNetwork)
                        loadRoot()
                    },
                )
            )
            add(
                RowList.Row(
                    title = "Delete once watched",
                    subtitle = "When an episode reaches the end",
                    accent = R.color.ff_badge_local,
                    trailing = if (store.downloadDeleteWatched) "On" else "Off",
                    payload = Entry {
                        store.downloadDeleteWatched = !store.downloadDeleteWatched
                        loadRoot()
                    },
                )
            )
        }
    }

    private fun accountRows(): List<RowList.Row> = buildList {
        add(
            RowList.Row(
                title = "Profile",
                subtitle = store.profileName ?: "Default",
                payload = Entry { startActivity(intent(this@SettingsActivity, MODE_PROFILE)) },
            )
        )
        add(
            RowList.Row(
                title = "Server",
                subtitle = store.serverName ?: "None",
                payload = Entry { startActivity(intent(this@SettingsActivity, MODE_SERVER)) },
            )
        )
        add(
            RowList.Row(
                title = "Libraries",
                subtitle = hiddenSummary(),
                payload = Entry { startActivity(intent(this@SettingsActivity, MODE_LIBRARIES)) },
            )
        )
        add(
            RowList.Row(
                title = "Sign out",
                subtitle = "Unlinks this device",
                payload = Entry { askSignOut() },
            )
        )
    }

    /**
     * The tour, and the two lines that say what the app is.
     *
     * The tour is shown once on the first launch and then never again unless it
     * is asked for, so this row is the only way back to it -- and someone
     * looking for it is looking for help with the keypad, not for something
     * filed under whichever account they signed in with.
     */
    private fun helpRows(): List<RowList.Row> = buildList {
        add(
            RowList.Row(
                title = getString(R.string.tour_title),
                subtitle = getString(R.string.tour_settings_note),
                payload = Entry { startActivity(TourActivity.review(this@SettingsActivity)) },
            )
        )
        add(
            RowList.Row(
                title = "FlipFlex ${versionName()} · a text-only Plex client for this handset. " +
                    "Everything it plays is converted by your server first, so every file in " +
                    "the library behaves the same way.",
                isBlurb = true,
            )
        )
        add(
            RowList.Row(
                title = getString(R.string.coffee_title),
                subtitle = COFFEE_URL.removePrefix("https://"),
                payload = Entry { openCoffee() },
            )
        )
    }

    /**
     * Open the tip page in the phone's browser, and say the address either way.
     *
     * Last row of the last tab, and nowhere else: the app never asks for this
     * unprompted, so someone reading it went looking for it.
     *
     * The subtitle carries the bare address on purpose. This is a 240x320 screen
     * with a T9 keypad in front of a payment form, which is not somewhere anyone
     * should have to finish the job -- reading the address off and typing it on
     * something with a keyboard is the likelier path, and it is the only one that
     * works if the browser has been disabled. Same reason the failure here is a
     * transient message rather than an error: there is nothing to recover from,
     * the address is on screen already.
     */
    private fun openCoffee() {
        val opened = runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(COFFEE_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!opened) showTransientMessage(getString(R.string.coffee_no_browser, COFFEE_URL))
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** Move to a tab and draw it. Out-of-range digits are ignored, not clamped. */
    private fun showTab(index: Int): Boolean {
        val strip = tabs ?: return false
        if (index !in 0 until strip.count) return false
        tab = index
        strip.setFocused(false)
        syncCursorOwner()
        // Not keepSelection: a different tab is a different list, and holding
        // the index would land the cursor on whatever happens to be third here.
        loadRoot()
        list.select(0)
        return true
    }

    /**
     * Turning direct play on is a question; turning it off is not.
     *
     * The asymmetry is the point. Off is the state everything is tested in and
     * the state that works on every file, so going back to it needs no
     * ceremony. On is an experiment that is *expected* to fail on part of any
     * library, and someone who switches it on without being told that reports it
     * as "playback is broken" -- which is the one report this note exists to
     * prevent.
     */
    private fun toggleDirectPlay() {
        if (store.directPlay) {
            store.directPlay = false
            loadRoot()
            return
        }
        confirm(
            heading = getString(R.string.direct_play_title),
            confirmLabel = getString(R.string.direct_play_go),
            note = getString(R.string.direct_play_note),
        ) {
            store.directPlay = true
            loadRoot()
        }
    }

    /**
     * Cycle rather than open a picker.
     *
     * Four presets in a fixed order, with the value on the row: pressing OK
     * four times returns you to where you started, which is the cheapest
     * possible undo on a device with no touchscreen. The details page opens a
     * panel for the same setting, because there the list can be nine subtitle
     * tracks long and cycling stops working.
     */
    private fun cycleQuality() {
        val i = Quality.PRESETS.indexOfFirst { it.id == store.quality }
        store.quality = Quality.PRESETS[(i + 1).mod(Quality.PRESETS.size)].id
        loadRoot()
    }

    private fun cycleDownloadQuality() {
        val i = Quality.PRESETS.indexOfFirst { it.id == store.downloadQuality }
        store.downloadQuality = Quality.PRESETS[(i + 1).mod(Quality.PRESETS.size)].id
        loadRoot()
    }

    private fun cycleSubtitleSize() {
        val steps = listOf(100, 125, 150, 200)
        val i = steps.indexOf(store.subtitleSize)
        store.subtitleSize = steps[(i + 1).mod(steps.size)]
        loadRoot()
    }

    private fun downloadSummary(): String {
        val n = Downloads.count(this)
        if (n == 0) return "Nothing saved yet"
        val pending = n - Downloads.countDone(this)
        val size = Downloads.humanBytes(Downloads.bytesOnDisk(this))
        return if (pending > 0) "$size · $pending pending" else size
    }

    private fun hiddenSummary(): String {
        val n = store.hiddenSections.size
        return if (n == 0) "All shown" else "$n hidden"
    }

    // ---- profiles ----------------------------------------------------------

    private fun loadProfiles() {
        setHeader("Profile")
        showMessage(null)
        setBusy(true)
        lifecycleScope.launch {
            val home = store.homeToken
            val users = if (home == null) emptyList() else PlexProfiles.users(home)
            setBusy(false)

            if (users.isEmpty()) {
                // An account with no Plex Home has exactly one identity, so
                // there is nothing to choose between. Say so rather than
                // showing an empty list that looks like a failed request.
                showMessage("This account has no\nPlex Home profiles.")
                return@launch
            }

            list.submit(
                users.map { u ->
                    RowList.Row(
                        title = u.title,
                        subtitle = when {
                            u.title == store.profileName -> "Active"
                            u.admin -> "Admin"
                            else -> ""
                        },
                        trailing = if (u.protected) "PIN" else "",
                        payload = Entry { switchTo(u) },
                    )
                }
            )
        }
    }

    private fun switchTo(user: PlexProfiles.User, pin: String? = null) {
        if (user.protected && pin == null) {
            askForPin(user)
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val home = store.homeToken
            val newToken = if (home == null) null else PlexProfiles.switchTo(home, user.uuid, pin)
            setBusy(false)
            if (newToken == null) {
                if (pin != null) {
                    // Almost always a wrong PIN rather than a transport failure,
                    // and offering another go is the only useful response.
                    askForPin(user, error = getString(R.string.pin_wrong))
                } else {
                    showMessage("Could not switch to ${user.title}.")
                }
                return@launch
            }
            store.token = newToken
            store.profileName = user.title

            // The server list and its per-server access tokens are scoped to the
            // identity that asked for them, so they have to be re-fetched. Not
            // doing this leaves the app talking to the old profile's server
            // token, which still works and silently reports watch state against
            // the wrong person.
            PlexServers.pick(newToken)?.let {
                store.serverUri = it.uri
                store.serverToken = it.token
                store.serverName = it.name
                store.serverClientId = it.serverId
            }
            goHome()
        }
    }

    // ---- PIN entry ---------------------------------------------------------

    /**
     * Ask for a Plex Home PIN.
     *
     * This is not a nicety, it is what stops profile switching being a one-way
     * door. The account this was built against has a **PIN-protected admin
     * profile**: without PIN entry, switching to any unprotected profile would
     * strand the device there, and the only way back to your own account would
     * be a full sign-out and re-link at plex.tv/link.
     *
     * Drawn over the list rather than as a separate activity, so cancelling
     * lands back on the profile list with no stack to unwind.
     */
    private fun askForPin(user: PlexProfiles.User, error: String? = null) {
        val view = layoutInflater.inflate(R.layout.pin_entry, null)
        addOverlay(view)
        pinView = view
        pinUser = user
        pinDigits = StringBuilder()

        view.findViewById<TextView>(R.id.pin_who).text = user.title
        error?.let { view.findViewById<TextView>(R.id.pin_status).text = it }
        setHeader(user.title)
        setSoftKeys(left = getString(R.string.soft_home), right = null)
        paintPin()
    }

    private fun paintPin() {
        val v = pinView ?: return
        // Four pips, filled as digits arrive. Plex Home PINs are always four.
        val shown = (0 until 4).joinToString(" ") { if (it < pinDigits.length) "●" else "○" }
        v.findViewById<TextView>(R.id.pin_pips).text = shown
    }

    private fun dismissPin() {
        pinView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        pinView = null
        pinUser = null
        pinDigits = StringBuilder()
        setSoftKeys(left = getString(R.string.soft_home), right = null)
        loadProfiles()
    }

    /** Returns true if the key was consumed by the PIN pad. */
    private fun handlePinKey(action: Action, keyCode: Int): Boolean {
        val user = pinUser ?: return false
        when (action) {
            Action.DIGIT -> {
                if (pinDigits.length < 4) pinDigits.append(KeyMap.digitOf(keyCode))
                paintPin()
                // Submit automatically on the fourth digit. There is no reason
                // to make someone press OK as well when the length is fixed.
                if (pinDigits.length == 4) {
                    val entered = pinDigits.toString()
                    pinView?.findViewById<TextView>(R.id.pin_status)?.text =
                        getString(R.string.msg_loading)
                    pinView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                    pinView = null
                    pinUser = null
                    pinDigits = StringBuilder()
                    switchTo(user, entered)
                }
            }
            Action.BACK -> {
                if (pinDigits.isEmpty()) dismissPin() else {
                    pinDigits.deleteCharAt(pinDigits.length - 1)
                    paintPin()
                }
            }
            Action.SELECT -> if (pinDigits.length == 4) {
                val entered = pinDigits.toString()
                pinDigits = StringBuilder()
                switchTo(user, entered)
            }
            else -> return false
        }
        return true
    }

    // ---- servers -----------------------------------------------------------

    private fun loadServers() {
        setHeader("Server")
        showMessage(null)
        setBusy(true)
        lifecycleScope.launch {
            val token = store.token
            // Every connection on the account, already ranked best-first. The
            // old "Change server" option called pick() and silently re-chose the
            // same one, which is why it appeared to do nothing at all -- there
            // was no way to see or choose an alternative.
            val conns = if (token == null) emptyList() else PlexServers.connections(token)
            setBusy(false)

            if (conns.isEmpty()) {
                showMessage(getString(R.string.msg_no_server))
                return@launch
            }

            // One row per server, not per connection: a server with four
            // addresses would otherwise fill the screen with rows that all do
            // the same thing. The ranking already put the best first, so the
            // first connection seen for a server is the one to offer.
            val seen = linkedMapOf<String, PlexServers.Connection>()
            conns.forEach { seen.putIfAbsent(it.serverId, it) }

            list.submit(
                seen.values.map { c ->
                    RowList.Row(
                        title = c.name,
                        subtitle = when {
                            c.relay -> "via Plex Relay"
                            c.local -> "On this network"
                            else -> "Remote"
                        },
                        trailing = if (c.serverId == store.serverClientId) "✓" else "",
                        payload = Entry { chooseServer(c) },
                    )
                }
            )
        }
    }

    private fun chooseServer(c: PlexServers.Connection) {
        setBusy(true)
        lifecycleScope.launch {
            // Re-run the picker pinned to this server, so we get whichever of
            // its addresses actually answers from here rather than the one that
            // happened to be listed first.
            val token = store.token
            val chosen = if (token == null) null else PlexServers.pick(token, c.serverId)
            setBusy(false)
            if (chosen == null) {
                showMessage("${c.name} is not reachable\nfrom this network.")
                return@launch
            }
            store.serverUri = chosen.uri
            store.serverToken = chosen.token
            store.serverName = chosen.name
            store.serverClientId = chosen.serverId
            goHome()
        }
    }

    // ---- libraries ---------------------------------------------------------

    private fun loadLibraries() {
        setHeader("Libraries")
        showMessage(null)
        setBusy(true)
        lifecycleScope.launch {
            val u = store.serverUri
            val t = store.serverToken
            val sections = if (u == null || t == null) emptyList() else PlexLibrary.sections(u, t)
            setBusy(false)
            if (sections.isEmpty()) {
                showMessage(getString(R.string.msg_offline))
                return@launch
            }
            renderLibraries(sections)
        }
    }

    private fun renderLibraries(sections: List<PlexItem>) {
        val hidden = store.hiddenSections
        list.submit(
            sections.map { s ->
                RowList.Row(
                    title = s.title,
                    subtitle = if (s.type == "show") "TV Shows" else "Movies",
                    // A tick for shown, blank for hidden. The wording on the
                    // action is "Show"/"Hide" rather than "pin"/"unpin"
                    // because the effect is visibility, and a 240px row has no
                    // space to explain a metaphor.
                    trailing = if (s.sectionKey in hidden) "" else "✓",
                    payload = Entry {
                        store.toggleSectionHidden(s.sectionKey)
                        renderLibraries(sections)
                    },
                )
            },
            keepSelection = true,
        )
    }

    // ---- sign out ----------------------------------------------------------

    /**
     * Ask before unlinking, in a panel rather than on a screen of its own.
     *
     * The first version was a whole activity whose two rows were Cancel and
     * Sign out. It asked, which was the point, but it looked exactly like every
     * other settings list -- so it read as another level of navigation rather
     * than as a question, and the answer to a question you did not notice being
     * asked is whatever the cursor was already on.
     *
     * The confirm panel is unmistakably modal: it darkens the screen it is over
     * and it has two entries. Recovering from a mistaken sign-out means finding
     * a second device with a browser and typing a code into plex.tv/link, which
     * is a long way to go for a mis-press on a phone with no touchscreen.
     */
    private fun askSignOut() {
        confirm("Sign out of Plex?", "Sign out") {
            store.signOut()
            startActivity(
                Intent(this, LinkActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
    }

    // ---- keys --------------------------------------------------------------

    override fun onHeaderFocusChanged(on: Boolean) {
        syncCursorOwner()
    }

    /**
     * Park the list whenever the tab strip or the header owns the cursor.
     *
     * Derived rather than set by each handler, for the reason written out in
     * BrowseActivity.syncCursorOwner: walking up off the strip onto the header
     * takes two calls, and whichever of them runs second decides what the list
     * looks like.
     */
    private fun syncCursorOwner() {
        list.parked = isHeaderFocused || tabs?.focused == true
    }

    override fun onAction(action: Action, keyCode: Int): Boolean {
        // The PIN pad owns every key while it is up, including BACK -- which
        // deletes a digit rather than leaving the screen until the field is
        // empty, the same as every other PIN field anyone has used. It owns the
        // digits too, which is why this runs before the tab shortcuts below.
        if (pinView != null && handlePinKey(action, keyCode)) return true

        val strip = tabs
        if (strip != null && strip.focused) {
            return when (action) {
                Action.LEFT -> { strip.move(-1); true }
                Action.RIGHT -> { strip.move(+1); true }
                Action.SELECT -> showTab(strip.cursor)
                Action.DOWN, Action.BACK -> {
                    strip.setFocused(false); syncCursorOwner(); true
                }
                // One more press of up leaves the strip for the header, which is
                // the only thing above it. The chain is list, tabs, title.
                Action.UP -> {
                    if (focusHeader(true)) { strip.setFocused(false) }
                    true
                }
                Action.DIGIT -> showTab(KeyMap.digitOf(keyCode) - 1)
                else -> false
            }
        }

        return when (action) {
            Action.UP -> list.move(-1) || focusTabs() || focusHeader(true)
            Action.DOWN -> list.move(+1)
            // Left is up a level, everywhere in the app that is not the player.
            // The lists here are all shorter than the screen now, so paging
            // them was the key's only other job and it had nothing to do.
            Action.LEFT -> { goUp(); true }
            Action.STAR -> list.move(-5)
            Action.RIGHT, Action.POUND -> list.move(+5)
            // A digit is the tab of that number. On a four-tab screen this is
            // the difference between two presses and nine.
            Action.DIGIT -> showTab(KeyMap.digitOf(keyCode) - 1)
            Action.SELECT -> if (list.rows.isEmpty()) { load(); true } else list.choose()
            else -> false
        }
    }

    /** Returns true when the strip took the cursor, so the caller can stop. */
    private fun focusTabs(): Boolean {
        val strip = tabs ?: return false
        strip.setFocused(true)
        syncCursorOwner()
        return true
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a sub-screen: the root's subtitles show the current
        // profile, server and hidden count, all of which may have just changed.
        if (mode == MODE_ROOT) loadRoot()
    }
}
