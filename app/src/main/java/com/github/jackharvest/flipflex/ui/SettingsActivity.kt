package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
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

        private const val EXTRA_MODE = "mode"

        fun intent(ctx: Context, mode: String = MODE_ROOT): Intent =
            Intent(ctx, SettingsActivity::class.java).putExtra(EXTRA_MODE, mode)
    }

    private lateinit var list: RowList
    private lateinit var mode: String

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
        setBody(list)
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
     * The whole settings list, grouped.
     *
     * Playback first, because those are the ones anyone actually changes --
     * this screen used to be four rows about accounts, and the settings people
     * wanted (subtitles, quality) did not exist anywhere. The captions matter
     * more than they look: at fifteen rows on a screen that shows seven, an
     * ungrouped list is one people scroll past rather than read.
     *
     * Every toggle acts in place and redraws, rather than opening a screen of
     * its own. A settings row whose value is on the row and changes when you
     * press it needs no explaining; a sub-screen for an on/off switch is two
     * extra presses and a back press.
     */
    private fun loadRoot() {
        setHeader("Settings")
        val q = Quality.byId(store.quality)
        val dq = Quality.byId(store.downloadQuality)
        list.submit(
            buildList {
                add(RowList.Row(title = "PLAYBACK", isHeader = true))
                add(
                    RowList.Row(
                        title = "Quality",
                        subtitle = q.summary,
                        trailing = q.label,
                        payload = Entry { cycleQuality() },
                    )
                )
                add(
                    RowList.Row(
                        title = "Subtitles",
                        subtitle = "Burned in by the server",
                        trailing = if (store.subtitles) "On" else "Off",
                        payload = Entry { store.subtitles = !store.subtitles; loadRoot() },
                    )
                )
                add(
                    RowList.Row(
                        title = "Subtitle size",
                        subtitle = "Plex's 100 is sized for a TV",
                        trailing = "${store.subtitleSize}%",
                        payload = Entry { cycleSubtitleSize() },
                    )
                )
                // An experiment, not a feature, and labelled as one. The obvious
                // suspect for a stream that dies partway through a busy evening
                // is the server's transcoder, and this is the only way to take
                // the transcoder out of the path and see whether the failures
                // stop. It will not work on everything -- the MT6739 decodes
                // HEVC only to 1600x960, so a 1080p HEVC source is above what
                // the chip accepts -- which is exactly why it is off by default
                // and why the subtitle says what it is for.
                add(
                    RowList.Row(
                        title = "Try direct play",
                        subtitle = "Skips the server's transcoder",
                        trailing = if (store.directPlay) "On" else "Off",
                        payload = Entry { store.directPlay = !store.directPlay; loadRoot() },
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

                add(RowList.Row(title = "DOWNLOADS", isHeader = true))
                add(
                    RowList.Row(
                        title = "Downloads",
                        subtitle = downloadSummary(),
                        trailing = Downloads.count(this@SettingsActivity).toString(),
                        payload = Entry { startActivity(DownloadsActivity.intent(this@SettingsActivity)) },
                    )
                )
                add(
                    RowList.Row(
                        title = "Download quality",
                        subtitle = dq.summary,
                        trailing = dq.label,
                        payload = Entry { cycleDownloadQuality() },
                    )
                )
                add(
                    RowList.Row(
                        title = "Wi-Fi only",
                        subtitle = "Never download on mobile data",
                        trailing = if (store.downloadWifiOnly) "On" else "Off",
                        payload = Entry {
                            store.downloadWifiOnly = !store.downloadWifiOnly
                            loadRoot()
                        },
                    )
                )
                add(
                    RowList.Row(
                        title = "Delete once watched",
                        subtitle = "When an episode reaches the end",
                        trailing = if (store.downloadDeleteWatched) "On" else "Off",
                        payload = Entry {
                            store.downloadDeleteWatched = !store.downloadDeleteWatched
                            loadRoot()
                        },
                    )
                )

                add(RowList.Row(title = "ACCOUNT", isHeader = true))
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
            },
            keepSelection = true,
        )
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

    override fun onAction(action: Action, keyCode: Int): Boolean {
        // The PIN pad owns every key while it is up, including BACK -- which
        // deletes a digit rather than leaving the screen until the field is
        // empty, the same as every other PIN field anyone has used.
        if (pinView != null && handlePinKey(action, keyCode)) return true

        return when (action) {
            Action.UP -> list.move(-1)
            Action.DOWN -> list.move(+1)
            // The root list is fifteen rows on a screen that shows seven, so it
            // needs paging like every other long list in the app.
            Action.LEFT, Action.STAR -> list.move(-7)
            Action.RIGHT, Action.POUND -> list.move(+7)
            Action.SELECT -> if (list.rows.isEmpty()) { load(); true } else list.choose()
            else -> false
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a sub-screen: the root's subtitles show the current
        // profile, server and hidden count, all of which may have just changed.
        if (mode == MODE_ROOT) loadRoot()
    }
}
