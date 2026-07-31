package com.github.jackharvest.flipflex.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.dl.Downloads
import com.github.jackharvest.flipflex.input.Action
import com.github.jackharvest.flipflex.plex.PlexItem
import com.github.jackharvest.flipflex.plex.PlexSearch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search, reached from the green call key on every screen but the player.
 *
 * ## Why this handset gets a search screen and PocketFlex does not
 *
 * PocketFlex omitted search deliberately: on a Miyoo Mini, typing a title one
 * letter at a time off an on-screen grid with a D-pad is slower than scrolling
 * to it. That reasoning does not survive a phone. This one has a numeric keypad
 * and ships a real system IME -- `com.iqqijni.dvt912key`, a 12-key keyboard
 * with T9 and prediction, confirmed as the only enabled input method on the
 * device -- so the fastest way to reach a title in a 1746-film library is to
 * type four letters of it.
 *
 * So the field here is a plain `EditText` and nothing more. Drawing our own
 * letter grid would be reimplementing T9 badly.
 *
 * ## Two focus states, and why the app owns the switch
 *
 * The field needs real Android focus, because that is what raises the IME. The
 * results list uses this app's own cursor, which is an integer that [RowList]
 * owns. Those two cannot both be live, so the screen tracks which one is, and
 * moves between them explicitly: down or OK from the field goes to the results
 * and takes the keyboard away, up from the top result comes back and brings it
 * out again. Leaving it to Android's focus system would mean the D-pad
 * sometimes moving a text cursor and sometimes moving the list, with nothing on
 * screen to say which.
 */
class SearchActivity : FlipActivity() {

    companion object {
        /**
         * How long typing has to stop before a request goes out.
         *
         * T9 emits a character per keypress, so searching per keystroke would
         * fire five requests to spell "queen" and show the results of "que"
         * last. Long enough to cover a settled word, short enough that nobody
         * waits for it deliberately.
         */
        private const val DEBOUNCE_MS = 450L

        /** Below this, results are noise: "a" matches most of a library. */
        private const val MIN_QUERY = 2
    }

    private lateinit var field: EditText
    private lateinit var list: RowList
    private lateinit var emptyState: TextView

    private var searching: Job? = null

    /** True while the text field owns the cursor and the IME is up. */
    private var typing = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = layoutInflater.inflate(R.layout.search_body, null)
        setBody(body)
        field = body.findViewById(R.id.search_field)
        emptyState = body.findViewById(R.id.search_message)
        list = RowList(this)
        // Under the empty-state text, which has to be able to cover it.
        body.findViewById<FrameLayout>(R.id.search_results).addView(list, 0)

        setHeader(getString(R.string.search_title))
        setSoftKeys(left = getString(R.string.soft_home), right = null)
        say(getString(R.string.search_prompt))

        list.onChoose = { _, row -> (row.payload as? PlexItem)?.let { open(it) } }
        list.parked = true
        // Somewhere for focus to go that is not the text field. The rows
        // themselves stay unfocusable -- selection is RowList's own integer, as
        // everywhere else in the app -- so D-pad keys land on the list, are not
        // consumed by it, and arrive at onKeyDown the way every other screen's
        // do.
        list.isFocusable = true
        list.isFocusableInTouchMode = true

        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = schedule(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        // actionSearch is what the IME's own confirm key sends. Treating it the
        // same as OK means the keyboard's go button and the D-pad centre agree,
        // which they must -- the user has no idea which one the app is watching.
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { toResults(); true } else false
        }

        toField()
    }

    /** The empty state. Null clears it and shows the results again. */
    private fun say(text: String?) {
        emptyState.text = text.orEmpty()
        emptyState.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---- focus -------------------------------------------------------------

    private fun toField() {
        typing = true
        list.parked = true
        field.requestFocus()
        field.setSelection(field.text.length)
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        setSoftKeys(left = getString(R.string.soft_home), right = null)
    }

    private fun toResults() {
        if (list.rows.isEmpty()) return
        typing = false
        list.parked = false
        // The IME covers most of a 320dp screen. Leaving it up while browsing
        // results would leave two rows visible out of seven.
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(field.windowToken, 0)
        // requestFocus on the list, NOT clearFocus on the field.
        //
        // clearFocus() was the first version and it does not work: the field is
        // the only focusable view on the screen, so Android has nowhere else to
        // put focus and hands it straight back. The field then kept the caret,
        // the IME stayed attached, and every OK press was swallowed before it
        // reached onKeyDown -- so the cursor moved through the results and
        // choosing one did nothing at all. Focus has to be given somewhere, not
        // taken away.
        list.requestFocus()
        setSoftKeys(left = getString(R.string.soft_home), right = getString(R.string.soft_options))
    }

    // ---- searching ---------------------------------------------------------

    private fun schedule(query: String) {
        searching?.cancel()
        if (query.trim().length < MIN_QUERY) {
            list.submit(emptyList())
            say(getString(R.string.search_prompt))
            return
        }
        searching = lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            run(query.trim())
        }
    }

    private suspend fun run(query: String) {
        val u = store.serverUri
        val t = store.serverToken
        if (u == null || t == null) {
            searchLocal(query)
            return
        }
        setBusy(true)
        val hubs = PlexSearch.search(u, t, query)
        setBusy(false)

        if (hubs.isEmpty()) {
            // Offline, or genuinely nothing. The downloads index can answer
            // either way and costs no network, so it is always worth asking.
            searchLocal(query, fallbackMessage = "Nothing matched “$query”.")
            return
        }

        say(null)
        list.submit(
            buildList {
                hubs.forEach { hub ->
                    add(RowList.Row(title = hub.title.uppercase(), isHeader = true))
                    hub.items.forEach { add(it.toRow()) }
                }
            }
        )
    }

    /**
     * Search what is on the phone.
     *
     * Used when there is no server and as a backstop when the server found
     * nothing -- a title you downloaded last week is exactly what you want the
     * search key to find on a train.
     */
    private fun searchLocal(query: String, fallbackMessage: String? = null) {
        val hits = Downloads.all(this).filter {
            it.state == Downloads.DONE &&
                (it.title.contains(query, true) || it.showTitle.contains(query, true))
        }
        if (hits.isEmpty()) {
            list.submit(emptyList())
            say(fallbackMessage ?: getString(R.string.msg_offline))
            return
        }
        say(null)
        list.submit(
            buildList {
                add(RowList.Row(title = "ON THIS PHONE", isHeader = true))
                hits.forEach { e ->
                    add(
                        RowList.Row(
                            title = e.title,
                            subtitle = listOfNotNull(
                                e.showTitle.ifEmpty { null },
                                e.code.ifEmpty { null },
                            ).joinToString("  "),
                            payload = PlexItem(
                                ratingKey = e.ratingKey,
                                key = "",
                                type = e.type,
                                title = e.title,
                                grandparentTitle = e.showTitle,
                                durationMs = e.durationMs,
                            ),
                        )
                    )
                }
            }
        )
    }

    /**
     * A search result names itself, always.
     *
     * Unlike a browse list, this one mixes types: an episode row next to a film
     * row next to a show row. So the show name goes on the episode even though
     * the browse screens drop it inside a season -- here there is no header
     * saying which show you are looking at, because you are not looking at one.
     */
    private fun PlexItem.toRow(): RowList.Row {
        val where = when (type) {
            "episode" -> listOfNotNull(
                grandparentTitle.ifEmpty { null },
                if (parentIndex >= 0 && index >= 0) "S$parentIndex · E$index" else null,
            ).joinToString("  ·  ")
            "season" -> parentTitle
            else -> subtitle()
        }
        return RowList.Row(
            title = title,
            subtitle = where,
            trailing = if (watched && !inProgress) "✓" else "",
            time = timeLabel(),
            progress = progress,
            payload = this,
        )
    }

    private fun open(item: PlexItem) {
        when {
            item.isContainer -> startActivity(
                BrowseActivity.intent(this, BrowseActivity.MODE_CHILDREN, item.ratingKey, item.title)
            )
            store.showDetails -> startActivity(DetailActivity.intent(this, item.ratingKey, item.title))
            else -> startActivity(
                PlayerActivity.intent(
                    this,
                    ratingKey = item.ratingKey,
                    title = item.title,
                    subtitle = item.subtitle(),
                    startMs = if (item.inProgress) item.viewOffsetMs else 0L,
                )
            )
        }
    }

    // ---- options -----------------------------------------------------------

    override fun optionsHeading(): String = list.selectedRow()?.title.orEmpty()

    /**
     * Small, but not empty.
     *
     * `toResults` labels the right softkey "Options", and a labelled softkey
     * that opens nothing is exactly what FlipActivity warns against -- so this
     * has to offer something real. Play skips the details page for the case
     * where the search *was* the decision, and Edit search is the way back to
     * the field for anyone who did not guess that up does it.
     */
    override fun optionsFor(): List<Option> = buildList {
        val item = list.selectedRow()?.payload as? PlexItem
        if (item != null && item.isPlayable) {
            add(
                Option(if (item.inProgress) "Resume" else "Play") {
                    startActivity(
                        PlayerActivity.intent(
                            this@SearchActivity,
                            ratingKey = item.ratingKey,
                            title = item.title,
                            subtitle = item.subtitle(),
                            startMs = if (item.inProgress) item.viewOffsetMs else 0L,
                        )
                    )
                }
            )
        }
        add(Option("Edit search") { toField() })
    }

    // ---- keys --------------------------------------------------------------

    /**
     * Already here. Pressing the search key again must not stack a second copy
     * of this screen behind the first, which is what the base implementation
     * would do -- and the back arrow would then walk through both.
     */
    override fun openSearch() = Unit

    /**
     * Only the keys the field does not want.
     *
     * A focused EditText consumes the digits, the letters and left/right (they
     * move the text cursor), so those never arrive here at all. What does
     * arrive is up, down, OK and Back -- which is exactly the set this screen
     * needs to switch between the field and the results.
     */
    override fun onAction(action: Action, keyCode: Int): Boolean {
        if (typing) {
            return when (action) {
                Action.DOWN, Action.SELECT -> { toResults(); true }
                // BACK is deliberately not handled here. While the IME is up it
                // never reaches us at all -- the keyboard takes it first, as a
                // delete and then as a dismiss, which is what the back arrow
                // does in every other text field on this phone. Once the IME has
                // gone it arrives, and the only thing left for it to mean is
                // "leave", so it falls through to the default. Clearing the
                // field here instead was the first version, and it made an
                // extra press that did something invisible.
                else -> false
            }
        }
        return when (action) {
            // Up off the top result goes back to the field and brings the
            // keyboard with it -- the field is directly above the list, so that
            // is the only thing the key can sensibly mean.
            Action.UP -> list.move(-1) || run { toField(); true }
            Action.DOWN -> list.move(+1)
            Action.STAR -> list.move(-7)
            Action.POUND -> list.move(+7)
            Action.SELECT -> list.choose()
            Action.BACK -> { toField(); true }
            else -> false
        }
    }

    override fun onPause() {
        super.onPause()
        searching?.cancel()
    }

    override fun onDestroy() {
        // The field's own view is going away with the activity, but the IME is
        // a separate process and will otherwise stay up over whatever screen
        // comes next.
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(field.windowToken, 0)
        super.onDestroy()
    }
}
