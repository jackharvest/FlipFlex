package io.github.jackharvest.flipflex.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.jackharvest.flipflex.R
import io.github.jackharvest.flipflex.input.Action
import io.github.jackharvest.flipflex.input.KeyMap
import io.github.jackharvest.flipflex.plex.PlexClient
import io.github.jackharvest.flipflex.store.Store

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

    private lateinit var headerTitle: TextView
    private lateinit var headerChevron: TextView
    private lateinit var headerBusy: TextView
    private lateinit var contentMessage: TextView
    private lateinit var softLeft: TextView
    private lateinit var softRight: TextView
    private lateinit var optionsScrim: View
    private lateinit var optionsTitle: TextView
    private lateinit var optionsList: LinearLayout

    /** An entry in the Options panel. */
    data class Option(val label: String, val run: () -> Unit)

    private var options: List<Option> = emptyList()
    private var optionIndex = 0
    private val optionsOpen: Boolean get() = optionsScrim.visibility == View.VISIBLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        PlexClient.clientId = store.clientId

        setContentView(R.layout.flip_shell)
        headerTitle = findViewById(R.id.header_title)
        headerChevron = findViewById(R.id.header_chevron)
        headerBusy = findViewById(R.id.header_busy)
        contentMessage = findViewById(R.id.content_message)
        softLeft = findViewById(R.id.soft_left)
        softRight = findViewById(R.id.soft_right)
        optionsScrim = findViewById(R.id.options_scrim)
        optionsTitle = findViewById(R.id.options_title)
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

    protected fun setHeader(title: String, showChevron: Boolean = true) {
        headerTitle.text = title
        headerChevron.visibility = if (showChevron) View.VISIBLE else View.GONE
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

    private fun openOptions() {
        options = optionsFor()
        if (options.isEmpty()) return

        optionIndex = 0
        optionsTitle.text = optionsHeading()
        optionsList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        options.forEach { opt ->
            val tv = inflater.inflate(R.layout.row_option, optionsList, false) as TextView
            tv.text = opt.label
            optionsList.addView(tv)
        }
        optionsScrim.visibility = View.VISIBLE
        paintOptions()
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
            if (event.action == KeyEvent.ACTION_DOWN) onAction(Action.SPARE, event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val action = KeyMap.actionOf(keyCode)

        // Volume is deliberately not ours. Re-implementing it would only get
        // the ringer/media stream distinction wrong.
        if (KeyMap.passThrough(action)) return super.onKeyDown(keyCode, event)

        if (optionsOpen) {
            when (action) {
                Action.UP -> moveOption(-1)
                Action.DOWN -> moveOption(+1)
                Action.SELECT -> {
                    val chosen = options.getOrNull(optionIndex)
                    closeOptions()
                    chosen?.run?.invoke()
                }
                // Both the back arrow and a second press of the right softkey
                // dismiss it. Two ways out of a modal panel on a device with no
                // touchscreen is insurance, not redundancy.
                Action.BACK, Action.SOFT_RIGHT -> closeOptions()
                Action.SOFT_LEFT -> { closeOptions(); goHome() }
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
}
