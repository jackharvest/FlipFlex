package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.input.Action
import com.github.jackharvest.flipflex.input.KeyMap
import com.github.jackharvest.flipflex.ui.PhoneDiagram.Part

/**
 * The keypad, one control at a time, on a drawing of the handset.
 *
 * ## Why this screen exists
 *
 * Everyone arriving at this app has spent a decade on touchscreens, and none of
 * what FlipFlex does with the keypad is guessable from looking at it. Three
 * things in particular are not:
 *
 * - **Neither soft key is Back.** Every other phone anyone has held put Back on
 *   one of them. Here the back arrow is a real physical key, so the soft keys
 *   are Home and Options -- see [FlipActivity] for why that trade was made.
 * - **The green call key is Search.** No app owns the call key. This one does,
 *   and a user who never presses it never finds search at all.
 * - **Two of the keys on the top row are not ours.** Favourites and messages
 *   leave the app entirely, which looks like a crash if you do not know it is
 *   the phone doing it. See the NOT delivered table in docs/keymap.md.
 *
 * ## Why a diagram and not a page of text
 *
 * A list of "left soft key: Home" lines requires the reader to work out which
 * lump of plastic "left soft key" means, on a keypad where the two soft keys
 * are unlabelled dashes. Pointing at it removes that step. [PhoneDiagram] draws
 * the handset and lights one control; this screen owns the words and the order.
 *
 * ## Driven by the keys it is teaching
 *
 * Up and down walk the steps, which is the first thing it teaches. Beyond that
 * the keys that are *not* used for walking jump straight to their own step:
 * press the green key and the tour explains the green key. That is the closest
 * thing to a touchscreen this handset has -- press the thing, read about the
 * thing -- and it costs one `when` branch.
 */
class TourActivity : FlipActivity() {

    companion object {
        private const val EXTRA_NEXT = "next"

        /**
         * The first launch: show the tour, then go on to where the app was
         * headed anyway.
         *
         * The onward intent travels in an extra rather than being worked out
         * here, because "where the app was headed" is the answer to a token
         * validation and a server search that [SplashActivity] has already
         * done. Repeating that decision in a second place is how the two get to
         * disagree.
         */
        fun firstRun(ctx: Context, next: Intent): Intent =
            Intent(ctx, TourActivity::class.java).putExtra(EXTRA_NEXT, next)

        /** Opened again from Settings, with nothing to go on to afterwards. */
        fun review(ctx: Context): Intent = Intent(ctx, TourActivity::class.java)
    }

    /**
     * One control, and what to say about it.
     *
     * [parts] is a set because two of these teach a pair of keys at once -- the
     * star and hash together, favourites and messages together -- and lighting
     * one of a pair would be teaching half a fact.
     *
     * [onRight] chooses which gutter the name goes in, and it is not decoration:
     * a label for the left soft key placed on the right would drag its leader
     * line across the D-pad and the whole keypad to get there. Controls on the
     * left of the handset get left-hand labels.
     */
    private data class Step(
        val parts: Set<Part>,
        val anchor: Part,
        val label: String,
        val onRight: Boolean,
        val title: String,
        val body: String,
    )

    /**
     * The order is by how soon you need it, not by where the key sits.
     *
     * Move and Select come first because nothing else can be reached without
     * them. The two soft keys come next because they are the ones no touchscreen
     * user has any intuition for. The keys that do nothing in this app come
     * last, where they belong: useful, but not before you can open anything.
     */
    private val steps = listOf(
        Step(
            parts = setOf(Part.DPAD_UD),
            anchor = Part.DPAD_UD,
            label = "Move",
            onRight = false,
            title = "Up and down",
            body = "Walks the amber bar through a list. Hold a key to run down a long " +
                "one. This is how you get everywhere.",
        ),
        Step(
            parts = setOf(Part.OK),
            anchor = Part.OK,
            label = "Select",
            onRight = true,
            title = "OK, in the middle",
            body = "Opens whatever the amber bar is sitting on. On the details page it " +
                "is also Play.",
        ),
        Step(
            parts = setOf(Part.BACK),
            anchor = Part.BACK,
            label = "Up a level",
            onRight = true,
            title = "The back arrow",
            body = "Goes up one level: episode to season, season to show. This is the " +
                "Back key. Neither soft key is.",
        ),
        Step(
            parts = setOf(Part.SOFT_LEFT, Part.SCREEN_LABEL_L),
            anchor = Part.SOFT_LEFT,
            label = "Home",
            onRight = false,
            title = "The left soft key",
            body = "Unwinds to the start screen from any depth. The label above it, at " +
                "the bottom of the screen, always says what it will do.",
        ),
        Step(
            parts = setOf(Part.SOFT_RIGHT, Part.SCREEN_LABEL_R),
            anchor = Part.SOFT_RIGHT,
            label = "Options",
            onRight = true,
            title = "The right soft key",
            body = "A menu for whatever is highlighted: shuffle a library, hide it, " +
                "download an episode, pick a subtitle track.",
        ),
        Step(
            parts = setOf(Part.CALL),
            anchor = Part.CALL,
            label = "Search",
            onRight = false,
            title = "The green key",
            body = "Opens search from any screen. FlipFlex takes this key before the " +
                "phone can, so it never dials anyone.",
        ),
        Step(
            parts = setOf(Part.STAR, Part.POUND, Part.DPAD_LR),
            anchor = Part.STAR,
            label = "Page",
            onRight = false,
            title = "Star and hash",
            body = "Jump about seven rows at a time. Left and right on the pad do the " +
                "same thing in a list.",
        ),
        Step(
            parts = setOf(Part.DPAD_LR),
            anchor = Part.DPAD_LR,
            label = "Seek",
            onRight = true,
            title = "While something plays",
            body = "Skip fifteen seconds. Up and down seek as well, whichever way round " +
                "you are holding the phone.",
        ),
        Step(
            parts = setOf(Part.DIGITS),
            anchor = Part.DIGITS,
            label = "PIN",
            onRight = true,
            title = "The number keys",
            body = "Only used for a Plex Home PIN, when you switch to a profile that has " +
                "one. Nothing else needs them.",
        ),
        Step(
            parts = setOf(Part.FAV, Part.MSG),
            anchor = Part.FAV,
            label = "Not ours",
            onRight = false,
            title = "Favourites and messages",
            body = "These belong to the phone. They open contacts and mail, and FlipFlex " +
                "never sees them. Nothing has crashed.",
        ),
        Step(
            parts = setOf(Part.HINGE),
            anchor = Part.HINGE,
            label = "Pause",
            onRight = true,
            title = "Closing the lid",
            // The last step, and the only place the tour says where to find
            // itself again. Anywhere earlier and it is an aside; here it is the
            // answer to "what if I forget one of these".
            body = "Pauses what is playing and tells Plex where you got to. Opening it " +
                "again does not start the sound up in your pocket.\n" +
                "Settings · Controls has all of this again.",
        ),
    )

    private lateinit var diagram: PhoneDiagram
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var footerView: TextView

    private var index = 0

    /** True when this is the first launch and something has to happen afterwards. */
    private val isFirstRun: Boolean get() = intent.hasExtra(EXTRA_NEXT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = layoutInflater.inflate(R.layout.tour_body, null)
        setBody(view)
        diagram = view.findViewById(R.id.tour_diagram)
        titleView = view.findViewById(R.id.tour_title)
        bodyView = view.findViewById(R.id.tour_body)
        footerView = view.findViewById(R.id.tour_footer)

        setHeader(getString(R.string.tour_title), showChevron = false)
        // "Skip" rather than "Home" on the first run, because there is no home
        // to go to yet -- the app has not been anywhere. The shell routes the
        // left soft key through goHome() regardless, which is overridden below.
        setSoftKeys(
            left = getString(if (isFirstRun) R.string.tour_skip else R.string.soft_done),
            right = getString(R.string.soft_options),
        )
        paint()
    }

    private fun paint() {
        val step = steps[index]
        diagram.show(step.parts, step.anchor, step.label, step.onRight)
        titleView.text = step.title
        bodyView.text = step.body
        footerView.text = getString(
            if (index == steps.size - 1) R.string.tour_footer_last else R.string.tour_footer,
            index + 1,
            steps.size,
        )
    }

    private fun go(delta: Int) {
        index = (index + delta).coerceIn(0, steps.size - 1)
        paint()
    }

    /** Jump to whichever step is about [part], if there is one. */
    private fun jumpTo(part: Part): Boolean {
        val i = steps.indexOfFirst { part in it.parts }
        if (i < 0) return false
        index = i
        paint()
        return true
    }

    /**
     * Leave, and go wherever the app was going before the tour interrupted it.
     *
     * On a review from Settings there is no onward intent and this is a plain
     * finish, which lands back on the settings list.
     */
    private fun finishTour() {
        @Suppress("DEPRECATION")
        val next = intent.getParcelableExtra<Intent>(EXTRA_NEXT)
        if (next != null) startActivity(next)
        finish()
    }

    /** The left soft key. See [FlipActivity.goHome] for how it arrives here. */
    override fun goHome() = finishTour()

    /**
     * The green key does not open search from inside the tour.
     *
     * The shell consumes CALL in `dispatchKeyEvent` and sends it here, and the
     * default would launch the search screen -- which on the step that is
     * explaining the green key is an odd thing to have happen mid-sentence.
     * Jumping to that step instead makes the key demonstrate itself.
     */
    override fun openSearch() {
        jumpTo(Part.CALL)
    }

    override fun optionsHeading(): String = getString(R.string.tour_title)

    override fun optionsFor(): List<Option> = buildList {
        if (index < steps.size - 1) add(Option(getString(R.string.tour_next)) { go(+1) })
        if (index > 0) add(Option(getString(R.string.tour_restart)) { index = 0; paint() })
        add(
            Option(getString(if (isFirstRun) R.string.tour_skip_all else R.string.soft_done)) {
                finishTour()
            }
        )
    }

    override fun onAction(action: Action, keyCode: Int): Boolean {
        when (action) {
            Action.DOWN, Action.RIGHT -> go(+1)
            Action.UP, Action.LEFT -> go(-1)
            // On the last step OK is what finishes, so the tour ends on the
            // same key that carried you through it rather than requiring the
            // user to find a different one at the end.
            Action.SELECT -> if (index == steps.size - 1) finishTour() else go(+1)
            // Back walks the tour backwards, and off the front of it. Falling
            // through to the shell's default here would drop a first-run user
            // out of the app entirely, because SplashActivity has already
            // finished itself by the time this screen is up.
            Action.BACK -> if (index == 0) finishTour() else go(-1)
            Action.STAR, Action.POUND -> jumpTo(Part.STAR)
            Action.DIGIT -> if (KeyMap.digitOf(keyCode) >= 0) jumpTo(Part.DIGITS)
            else -> return false
        }
        return true
    }
}
