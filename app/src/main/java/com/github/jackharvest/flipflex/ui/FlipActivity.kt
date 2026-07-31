package com.github.jackharvest.flipflex.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.dl.Downloads
import com.github.jackharvest.flipflex.input.Action
import com.github.jackharvest.flipflex.input.KeyMap
import com.github.jackharvest.flipflex.plex.PlexClient
import com.github.jackharvest.flipflex.store.Net
import com.github.jackharvest.flipflex.store.NetPolicy
import com.github.jackharvest.flipflex.store.Store

/**
 * The shell every FlipFlex screen lives in, and the one place keys are routed.
 *
 * ## The two softkeys
 *
 * This handset gives an app exactly two softkeys, one dedicated back arrow, a
 * D-pad with a centre press, the digits, and `CALL`. That is the entire input
 * budget, and it is measured -- see the VERIFIED section of docs/keymap.md,
 * which corrected the stock `.kl` files on this exact point.
 *
 * So the assignment is:
 *
 * | Input | Action |
 * |---|---|
 * | Left softkey | **Home** -- back to the start screen from any depth |
 * | Right softkey | **Options** -- context menu for the focused row |
 * | Back arrow | Up one level |
 * | D-pad centre | Select |
 *
 * Neither softkey is Back. The proof-of-concept art drew them as
 * `Back | Select`, but that was drawn before we knew whether the dedicated back
 * arrow reached an app -- and it does, as an ordinary `KEYCODE_BACK` with a real
 * scancode. Spending one of only two softkeys on a function that already has a
 * physical button would waste half the budget, and it would leave no key at all
 * for the per-item actions that Plex puts behind its three-dot menu.
 */
abstract class FlipActivity : AppCompatActivity() {

    protected lateinit var store: Store

    private lateinit var headerBar: View
    private lateinit var headerTitle: TextView
    private lateinit var headerChevron: TextView
    private lateinit var headerBusy: TextView
    private lateinit var contentMessage: TextView
    private lateinit var softLeft: TextView
    private lateinit var softRight: TextView
    private lateinit var optionsScrim: View
    private lateinit var optionsTitle: TextView
    private lateinit var optionsNote: TextView
    private lateinit var optionsList: LinearLayout

    /** An entry in the Options panel. */
    data class Option(val label: String, val run: () -> Unit)

    private var options: List<Option> = emptyList()
    private var optionIndex = 0
    private val optionsOpen: Boolean get() = optionsScrim.visibility == View.VISIBLE

    /** Set by [showTransientMessage]; the next key press takes the message down. */
    private var messageIsTransient = false

    /** True while the `‹ Title` header is the cursor. See [focusHeader]. */
    private var headerFocused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        PlexClient.clientId = store.clientId

        setContentView(R.layout.flip_shell)
        headerBar = findViewById(R.id.header_bar)
        headerTitle = findViewById(R.id.header_title)
        headerChevron = findViewById(R.id.header_chevron)
        headerBusy = findViewById(R.id.header_busy)
        contentMessage = findViewById(R.id.content_message)
        softLeft = findViewById(R.id.soft_left)
        softRight = findViewById(R.id.soft_right)
        optionsScrim = findViewById(R.id.options_scrim)
        optionsTitle = findViewById(R.id.options_title)
        optionsNote = findViewById(R.id.options_note)
        optionsList = findViewById(R.id.options_list)

        setSoftKeys(left = "Home", right = "Options")
    }

    // ---- shell -------------------------------------------------------------

    /** Put a screen's own view into the content frame, under the options panel. */
    protected fun setBody(view: View) {
        val frame = findViewById<android.widget.FrameLayout>(R.id.content)
        // index 0 keeps this beneath content_message and options_scrim, which
        // both have to be able to cover it.
        frame.addView(view, 0)
    }

    /**
     * Put a view *over* the body but under the message and options panel.
     *
     * [setBody] deliberately inserts at index 0, so passing a modal to it puts
     * the modal behind the list -- which is exactly what happened to the PIN
     * pad: it rendered, took keys, and was drawn underneath the profile list it
     * was supposed to replace.
     */
    protected fun addOverlay(view: View) {
        val frame = findViewById<android.widget.FrameLayout>(R.id.content)
        val message = findViewById<View>(R.id.content_message)
        frame.addView(view, frame.indexOfChild(message).coerceAtLeast(0))
    }

    protected fun setHeader(title: String, showChevron: Boolean = true) {
        headerTitle.text = title
        headerChevron.visibility = if (showChevron) View.VISIBLE else View.GONE
        // A header that is not offering to go anywhere must not be a place the
        // cursor can get stuck: Home draws no chevron, and Home is where up
        // stops.
        if (!showChevron && headerFocused) focusHeader(false)
        paintHeader()
    }

    // ---- the header as a control -------------------------------------------

    /**
     * Put the cursor on the `‹ Title` header, where OK goes up a level.
     *
     * ## Why the header is pressable at all
     *
     * Two reasons, and the second is the serious one. The first is that it is
     * what the chevron has always looked like it meant -- every touchscreen this
     * user has held has a back arrow in exactly that corner, and on this handset
     * it was decoration. The second is that **the back arrow is a physical key
     * that can break**, and on a phone this age that is not hypothetical. With
     * left-as-back and this, going up a level survives losing it.
     *
     * Reached by pressing up off the top of a screen -- past the tab strip where
     * there is one -- so it is the end of the same chain the tabs are on rather
     * than a separate gesture to learn.
     *
     * Returns false, and does nothing, where there is no level to go up to: the
     * home screen draws no chevron for exactly that reason.
     */
    protected fun focusHeader(on: Boolean): Boolean {
        if (on && headerChevron.visibility != View.VISIBLE) return false
        if (headerFocused == on) return on
        headerFocused = on
        paintHeader()
        onHeaderFocusChanged(on)
        return true
    }

    protected val isHeaderFocused: Boolean get() = headerFocused

    /**
     * Tell the screen its list no longer owns the cursor.
     *
     * Every screen with a list parks it here. Two amber bars is two cursors --
     * the same reason [RowList.parked] exists for the tab strip and the A-Z rail.
     */
    protected open fun onHeaderFocusChanged(on: Boolean) {}

    /** Up one level, as the back arrow does it. Overridden where Back is special. */
    protected open fun goUp() {
        @Suppress("DEPRECATION")
        onBackPressed()
    }

    private fun paintHeader() {
        val lit = headerFocused
        headerBar.setBackgroundColor(if (lit) getColor(R.color.ff_amber) else 0)
        headerTitle.setTextColor(getColor(if (lit) R.color.ff_ground else R.color.ff_text))
        headerChevron.setTextColor(getColor(if (lit) R.color.ff_ground else R.color.ff_text_dim))
    }

    /**
     * Give the whole panel to the body: no header, no softkey bar, no status bar.
     *
     * Only the player uses this, and the arithmetic is why. Rotated, the panel
     * is 320x240. The status bar takes ~24, the header ~26 and the softkey bar
     * ~22, which is 72 of 240 -- **thirty percent of the screen** spent on
     * chrome, on a device where the picture is the entire point. Immersive gets
     * all of it back, and the player draws its own auto-hiding copy of the two
     * softkey labels over the video instead.
     *
     * The status bar is hidden via WindowInsetsController because this app is
     * API 30 only; `systemUiVisibility` is deprecated from exactly this release.
     * BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE is set so the bar can still be
     * summoned, which matters because there is no touchscreen to swipe with and
     * the alternative -- a bar that can never come back -- is worse.
     */
    protected fun setImmersive() {
        findViewById<View>(R.id.header_bar).visibility = View.GONE
        findViewById<View>(R.id.header_rule).visibility = View.GONE
        findViewById<View>(R.id.softkey_bar).visibility = View.GONE
        findViewById<View>(R.id.softkey_rule).visibility = View.GONE

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    protected fun setSoftKeys(left: String?, right: String?) {
        softLeft.text = left.orEmpty()
        softRight.text = right.orEmpty()
    }

    protected fun setBusy(busy: Boolean, label: String = "…") {
        headerBusy.text = label
        headerBusy.visibility = if (busy) View.VISIBLE else View.GONE
    }

    /** Cover the content with a message. Pass null to clear it. */
    protected fun showMessage(text: String?) {
        contentMessage.text = text.orEmpty()
        contentMessage.visibility = if (text == null) View.GONE else View.VISIBLE
        messageIsTransient = false
    }

    /**
     * A message that gets out of the way by itself, on the next key press.
     *
     * The message view covers the whole content frame, which is right for "no
     * server" and wrong for "that shuffle found nothing" -- the second one is a
     * remark about an action, and leaving it parked over a perfectly good list
     * until something happens to reload it makes the app look broken instead of
     * the action having failed.
     */
    protected fun showTransientMessage(text: String) {
        showMessage(text)
        messageIsTransient = true
    }

    // ---- navigation --------------------------------------------------------

    /**
     * Unwind to the start screen.
     *
     * `CLEAR_TOP` rather than starting a fresh Home: the point of the Home
     * softkey is to escape from four levels deep in a show, and leaving those
     * four activities alive underneath would mean the back arrow walks straight
     * back down into them.
     */
    protected open fun goHome() {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    // ---- options panel -----------------------------------------------------

    /**
     * What the right softkey offers on this screen, for whatever is focused.
     *
     * An empty list means the panel does not open at all, and the screen should
     * label its right softkey with something other than "Options" -- an
     * Options key that visibly does nothing is worse than a blank label.
     */
    protected open fun optionsFor(): List<Option> = emptyList()

    /** The line above the options, normally the focused item's title. */
    protected open fun optionsHeading(): String = ""

    private fun openOptions() = showPanel(optionsHeading(), optionsFor())

    private fun showPanel(heading: String, entries: List<Option>, note: String? = null) {
        if (entries.isEmpty()) return
        options = entries
        optionIndex = 0
        optionsTitle.text = heading
        optionsNote.text = note.orEmpty()
        optionsNote.visibility = if (note == null) View.GONE else View.VISIBLE
        optionsList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        entries.forEach { opt ->
            val tv = inflater.inflate(R.layout.row_option, optionsList, false) as TextView
            tv.text = opt.label
            optionsList.addView(tv)
        }
        optionsScrim.visibility = View.VISIBLE
        paintOptions()
    }

    /**
     * "Are you sure", in the panel the Options key already opens.
     *
     * Not a Dialog, for the same reason the Options panel is not one: a Dialog
     * on this build steals D-pad focus unpredictably, and losing the D-pad on a
     * device with no touch input is unrecoverable. Reusing the panel also means
     * the confirm is driven by exactly the keys the user just used to get here.
     *
     * Cancel is first, so it is what the cursor starts on -- the destructive row
     * must never be the one an accidental OK press lands on.
     */
    /**
     * "Pick one of these", in the panel the Options key already opens.
     *
     * The details page uses it for subtitle tracks, audio tracks and quality --
     * lists that are two entries long on one file and nine on another, so a
     * left/right toggle in the row would work for some items and be unusable
     * for others. [startAt] puts the cursor on whatever is already chosen,
     * which is what makes a nine-track list navigable at all.
     */
    protected fun chooseFrom(heading: String, entries: List<Option>, startAt: Int = 0) {
        showPanel(heading, entries)
        if (startAt in entries.indices) {
            optionIndex = startAt
            paintOptions()
        }
    }

    /**
     * [note] is the paragraph between the question and the two answers.
     *
     * Optional, because most confirmations are about something the user is
     * looking at -- "Delete Ocean's Thirteen?" needs no explaining. It exists
     * for the two that are not: turning direct play on, where the answer depends
     * on what the SoC can decode, and streaming over mobile data, where it
     * depends on what somebody's plan costs. Both of those are decisions nobody
     * can make from a title alone, and a question that cannot be answered is
     * one people learn to dismiss.
     */
    protected fun confirm(
        heading: String,
        confirmLabel: String,
        note: String? = null,
        onConfirm: () -> Unit,
    ) {
        showPanel(
            heading,
            listOf(
                Option("Cancel") {},
                Option(confirmLabel, onConfirm),
            ),
            note = note,
        )
    }

    // ---- playback, and what it may cost ------------------------------------

    /**
     * Open the player, having first checked this is not about to spend a data
     * allowance somebody was saving.
     *
     * ## Why this is in the shell and not in the player
     *
     * Six screens start playback, and the check has to happen *before* the
     * player exists: it is a question, and a question asked over a screen that
     * has already torn down the list behind it has nowhere to go back to when
     * the answer is no. It also has to be asked before the transcode is
     * requested, or the server has already begun spending the data the dialog is
     * about.
     *
     * A downloaded copy is never guarded. The player prefers the local file --
     * that is the entire point of the download -- so the radio is not involved
     * and a warning about mobile data would simply be wrong.
     */
    protected fun startPlayback(playerIntent: android.content.Intent) {
        val key = playerIntent.getStringExtra(PlayerActivity.EXTRA_KEY).orEmpty()
        val isLocal = key.isNotEmpty() && Downloads.playableFile(this, key) != null
        if (isLocal || !Net.metered(this)) {
            startActivity(playerIntent)
            return
        }
        when (store.streamNetwork) {
            NetPolicy.WIFI_ONLY -> showTransientMessage(getString(R.string.net_stream_blocked))
            NetPolicy.ASK -> confirm(
                heading = getString(R.string.net_stream_title),
                confirmLabel = getString(R.string.net_stream_go),
                note = getString(R.string.net_stream_note),
            ) { startActivity(playerIntent) }
            else -> startActivity(playerIntent)
        }
    }

    /**
     * The same question for a download, answered where the user pressed the
     * button rather than silently in the service.
     *
     * Under [NetPolicy.WIFI_ONLY] the download is still queued -- it will start
     * by itself on the next Wi-Fi -- because a queue that waits is the feature,
     * and refusing to write the row would make the user come back and press it
     * again. [onGo] runs either way; the difference is what the screen says
     * afterwards, which is why the caller is told which it was.
     */
    protected fun startDownload(onGo: (waitsForWifi: Boolean) -> Unit) {
        if (!Net.metered(this)) {
            onGo(false)
            return
        }
        when (store.downloadNetwork) {
            NetPolicy.WIFI_ONLY -> onGo(true)
            NetPolicy.ASK -> confirm(
                heading = getString(R.string.net_download_title),
                confirmLabel = getString(R.string.net_download_go),
                note = getString(R.string.net_download_note),
            ) { onGo(false) }
            else -> onGo(false)
        }
    }

    protected fun closeOptions() {
        optionsScrim.visibility = View.GONE
    }

    private fun paintOptions() {
        for (i in 0 until optionsList.childCount) {
            val tv = optionsList.getChildAt(i) as TextView
            val on = i == optionIndex
            tv.setBackgroundColor(if (on) getColor(R.color.ff_amber) else 0)
            tv.setTextColor(getColor(if (on) R.color.ff_ground else R.color.ff_text))
        }
    }

    private fun moveOption(delta: Int) {
        if (options.isEmpty()) return
        optionIndex = (optionIndex + delta).coerceIn(0, options.size - 1)
        paintOptions()
    }

    // ---- key routing -------------------------------------------------------

    /**
     * `CALL` has to be caught here rather than in `onKeyDown`.
     *
     * By the time an unhandled key reaches `onKeyDown` the framework has
     * already decided what to do with it, and for `CALL` that is "open the
     * dialer" -- which on this handset means the app disappears mid-episode.
     * Consuming it in dispatch is what keeps it ours.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyMap.mustInterceptEarly(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // The panel is modal and has to be dismissed rather than left
                // hanging behind a new screen -- coming back from search to a
                // half-open Options menu over an unrelated list is the kind of
                // thing that reads as a crash.
                if (optionsOpen) closeOptions()
                openSearch()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * What the green call key does, everywhere.
     *
     * `CALL` is the one genuinely spare hardware button this handset gives an
     * app -- measured in docs/keymap.md, where the email, contacts and speaker
     * keys all turned out to be bound above the app layer. Spending it on
     * search is what makes search worth having: the alternative is a row on the
     * home screen, which is three presses away from anywhere you would actually
     * want to search from.
     *
     * It has to be consumed in `dispatchKeyEvent`, above, or the framework
     * opens the dialer and the app disappears.
     *
     * Overridden to nothing by the player, which must not be torn down
     * mid-episode by a key press, and by the search screen itself.
     */
    protected open fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val action = KeyMap.actionOf(keyCode)

        // Volume is deliberately not ours. Re-implementing it would only get
        // the ringer/media stream distinction wrong.
        if (KeyMap.passThrough(action)) return super.onKeyDown(keyCode, event)

        // Announced before anything is decided, including the softkeys, which
        // never reach onAction. The player needs *every* key it keeps to count
        // as activity, because that is the only way its auto-hidden controls
        // can be brought back -- there is no touchscreen to tap.
        onKeyPressed(action)

        // The key still does its normal job; it only also dismisses the remark.
        //
        // Back is the exception, and it was a real bug. "This file has no
        // subtitle tracks" fills the content frame, so it reads as a screen --
        // and the obvious way off a screen is Back, which dismissed the message
        // *and* left the details page, landing the user two levels up in the
        // library with no idea why. Taking the remark down is a whole job for
        // one key press.
        if (messageIsTransient) {
            showMessage(null)
            if (action == Action.BACK) return true
        }

        // The header is a control while it is lit, and everything else on the
        // screen is parked behind it.
        if (headerFocused) {
            when (action) {
                Action.SELECT, Action.BACK -> { focusHeader(false); goUp() }
                Action.DOWN -> focusHeader(false)
                Action.SOFT_LEFT -> { focusHeader(false); goHome() }
                Action.SOFT_RIGHT -> { focusHeader(false); openOptions() }
                // Left is back everywhere else in the app, and the header is
                // where back already lives. Up is swallowed: this is the top.
                Action.LEFT -> { focusHeader(false); goUp() }
                else -> Unit
            }
            return true
        }

        if (optionsOpen) {
            // The panel is a vertical list drawn on the screen, so it has to be
            // driven by whichever key points down *the screen* -- which is not
            // the key printed "down" once the player has turned the handset
            // ninety degrees. Both are accepted: the printed key keeps working,
            // and the one that points the right way starts working. No key ends
            // up dead, and none of them moves the wrong way.
            val dir = screenDirection(action)
            when {
                action == Action.UP || dir == Action.UP -> moveOption(-1)
                action == Action.DOWN || dir == Action.DOWN -> moveOption(+1)
                action == Action.SELECT -> {
                    val chosen = options.getOrNull(optionIndex)
                    closeOptions()
                    chosen?.run?.invoke()
                }
                // Both the back arrow and a second press of the right softkey
                // dismiss it. Two ways out of a modal panel on a device with no
                // touchscreen is insurance, not redundancy.
                action == Action.BACK || action == Action.SOFT_RIGHT -> closeOptions()
                action == Action.SOFT_LEFT -> { closeOptions(); goHome() }
                else -> Unit
            }
            return true
        }

        return when (action) {
            null -> super.onKeyDown(keyCode, event)
            Action.SOFT_LEFT -> { goHome(); true }
            Action.SOFT_RIGHT -> { openOptions(); true }
            else -> onAction(action, keyCode) || super.onKeyDown(keyCode, event)
        }
    }

    /**
     * A screen's own handling. Return true if the key was used.
     *
     * `Action.BACK` arrives here too, so a screen that needs to intercept it --
     * the player, which must stop the transcode before leaving -- can, and
     * anything that does not simply returns false and gets the default.
     */
    protected open fun onAction(action: Action, keyCode: Int): Boolean = false

    /**
     * Any key this app keeps, before anything has decided what to do with it.
     *
     * Fires for the softkeys and for keys the Options panel is about to consume,
     * neither of which reach [onAction]. Only the player uses it.
     */
    protected open fun onKeyPressed(action: Action?) {}

    /**
     * Where a D-pad key points *on this screen*, which stops being where it is
     * printed as soon as a screen is rotated.
     *
     * Every screen but the player is portrait, so the identity mapping is right
     * for all of them. The player overrides it, and only the Options panel
     * consults it -- a screen that draws its own controls over a rotated video
     * knows better than the shell does what its keys mean.
     */
    protected open fun screenDirection(action: Action?): Action? = action
}
