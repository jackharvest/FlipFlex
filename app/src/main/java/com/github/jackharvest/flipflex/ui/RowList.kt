package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.jackharvest.flipflex.R

/**
 * The list. Every FlipFlex screen that shows more than one thing uses this.
 *
 * Selection is an index this class owns, **not** Android's focus system. That
 * is a deliberate departure from how a normal Android app would do it, for two
 * reasons specific to this device:
 *
 * - the list is reloaded from the network under the user, and restoring focus
 *   across a `notifyDataSetChanged` is famously unreliable, whereas restoring
 *   an integer is not;
 * - the whole app is driven by a D-pad with no touchscreen, so there is never a
 *   second focusable thing on screen competing for it. The generality of the
 *   focus system buys nothing here and costs predictability.
 */
class RowList(context: Context) : RecyclerView(context) {

    /** What a row displays. Filled from a PlexItem, or built by hand for menus. */
    data class Row(
        val title: String,
        val subtitle: String = "",
        val trailing: String = "",
        val time: String = "",
        val progress: Float = 0f,
        /** Whatever the screen needs back when this row is chosen. */
        val payload: Any? = null,
        /**
         * A group caption rather than a selectable row.
         *
         * This is how the Recommended view is possible at all. Plex's own
         * clients draw those groups as horizontal carousels, which needs
         * hundreds of pixels of width and a pointer -- neither of which exists
         * here. A vertical list with captions carries the same grouping in the
         * one dimension this screen has.
         *
         * Headers are skipped by [move], so the cursor never lands on one and
         * the user never has to press past a row that does nothing.
         */
        val isHeader: Boolean = false,
        /**
         * A one-line row at about half height, for "more" affordances.
         *
         * The Recommended view shows three entries per group and then one of
         * these. That shape only works if the button is genuinely cheap
         * vertically -- a full-height row costs the same as a fourth result,
         * which would defeat the point of capping the group in the first place.
         *
         * Unlike [isHeader] it is still selectable: it is a button.
         */
        val isThin: Boolean = false,
        /**
         * A paragraph of prose rather than a row: a Plex summary.
         *
         * Wraps instead of ellipsizing, and is not selectable. It exists so the
         * details page can be one list with one cursor rather than a scrolling
         * text view sitting above a list, which on a D-pad means two things that
         * both want the down key and no way to say which has it.
         */
        val isBlurb: Boolean = false,
    )

    var rows: List<Row> = emptyList()
        private set

    var selected: Int = 0
        private set

    /**
     * True while some other control on the screen owns the cursor -- the letter
     * rail, or the library view tabs.
     *
     * The selection bar is how this app shows where you are, so leaving it lit
     * while the tab strip is also lit puts two cursors on a 240dp screen and
     * makes it genuinely unclear which one OK is about to act on. Parked keeps
     * the row marked, in a colour that is plainly not the live one.
     */
    var parked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            adapter.notifyItemChanged(selected)
        }

    /** Called on D-pad centre. */
    var onChoose: ((Int, Row) -> Unit)? = null

    /** Called when selection moves, for screens that mirror it in the header. */
    var onMove: ((Int, Row) -> Unit)? = null

    private val adapter = RowAdapter()

    init {
        layoutManager = LinearLayoutManager(context)
        setAdapter(adapter)
        // Nothing here is touch-driven, and an overscroll glow on a 2.4" panel
        // is just noise.
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        itemAnimator = null
    }

    fun submit(newRows: List<Row>, keepSelection: Boolean = false) {
        val previous = selected
        rows = newRows
        selected = if (keepSelection) {
            previous.coerceIn(0, (newRows.size - 1).coerceAtLeast(0))
        } else {
            0
        }
        // A list that opens with a caption or a paragraph at the top must not
        // start with the cursor on it, or the first press of OK does nothing.
        // The details page opens with a summary, so this is its normal case
        // rather than an edge one.
        val head = rows.getOrNull(selected)
        if (head != null && (head.isHeader || head.isBlurb)) {
            selected = nextSelectable(selected, +1) ?: selected
        }
        adapter.notifyDataSetChanged()
        if (newRows.isNotEmpty()) {
            // Scroll to the top whenever everything above the cursor is
            // unselectable, rather than to the cursor itself.
            //
            // They are different exactly when a list opens with captions or
            // prose: the cursor lands on the first row it *can* land on, and
            // scrolling to that pushes everything above it off the screen. It
            // cost the grouped Settings list its first "PLAYBACK" caption and
            // the details page its entire description -- on the one screen
            // whose job is to show you a description.
            //
            // Phrased as a property of the rows rather than of `keepSelection`
            // because it is true on both paths: a screen that redraws itself
            // after a toggle wants to keep the cursor *and* still be at the top
            // if that is where the cursor is.
            val anchor = if (rows.take(selected).all { it.isHeader || it.isBlurb }) 0 else selected
            scrollToPosition(anchor)
            onMove?.invoke(selected, newRows[selected])
        }
    }

    /** The next index in [dir] that is not a caption, or null if there is none. */
    private fun nextSelectable(from: Int, dir: Int): Int? {
        var i = from
        while (i in rows.indices) {
            if (!rows[i].isHeader && !rows[i].isBlurb) return i
            i += dir
        }
        return null
    }

    fun move(delta: Int): Boolean {
        if (rows.isEmpty()) return false
        val dir = if (delta < 0) -1 else +1
        val wanted = (selected + delta).coerceIn(0, rows.size - 1)
        // Step over captions in the direction of travel, then back the other
        // way if we ran off the end -- otherwise the last group's caption traps
        // the cursor at the bottom of the list.
        val next = nextSelectable(wanted, dir) ?: nextSelectable(wanted, -dir) ?: return false
        if (next == selected) return false
        val was = selected
        selected = next
        adapter.notifyItemChanged(was)
        adapter.notifyItemChanged(next)
        // Bring the caption along when arriving at the first row of a group,
        // so you can see which group you have just moved into.
        val anchor = if (rows.getOrNull(next - 1)?.isHeader == true) next - 1 else next
        // scrollToPosition, not smoothScroll: on a 2.4" list the animation is
        // longer than the gap between two D-pad presses when someone holds the
        // key down, and the list visibly lags behind the cursor.
        (layoutManager as LinearLayoutManager).scrollToPosition(anchor)
        onMove?.invoke(next, rows[next])
        return true
    }

    fun choose(): Boolean {
        val row = rows.getOrNull(selected) ?: return false
        onChoose?.invoke(selected, row)
        return true
    }

    fun selectedRow(): Row? = rows.getOrNull(selected)

    /** Put the cursor somewhere specific, e.g. restoring after coming back. */
    fun select(index: Int) {
        if (rows.isEmpty()) return
        val was = selected
        selected = index.coerceIn(0, rows.size - 1)
        adapter.notifyItemChanged(was)
        adapter.notifyItemChanged(selected)
        scrollToPosition(selected)
    }

    private inner class RowAdapter : Adapter<RowHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.row_item, parent, false)
            )

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            holder.bind(rows[position], position == selected)
        }
    }

    private inner class RowHolder(view: View) : ViewHolder(view) {
        private val root: LinearLayout = view.findViewById(R.id.row_root)
        private val title: TextView = view.findViewById(R.id.row_title)
        private val subtitle: TextView = view.findViewById(R.id.row_subtitle)
        private val trailing: TextView = view.findViewById(R.id.row_trailing)
        private val time: TextView = view.findViewById(R.id.row_time)
        private val secondLine: View = view.findViewById(R.id.row_second_line)
        private val progress: ProgressBar = view.findViewById(R.id.row_progress)

        fun bind(row: Row, isSelected: Boolean) {
            if (row.isBlurb) {
                bindBlurb(row)
                return
            }
            if (row.isHeader) {
                bindHeader(row)
                return
            }
            if (row.isThin) {
                bindThin(row, isSelected)
                return
            }
            root.setPadding(dp(8), dp(4), dp(8), dp(4))
            title.textSize = 13f
            oneLine()
            title.text = row.title
            subtitle.text = row.subtitle
            trailing.text = row.trailing
            time.text = row.time

            // Collapse the second line entirely when there is nothing on it.
            // Leaving an empty 10sp TextView in place costs ~14dp of a 270dp
            // content area -- most of a row, on every row.
            secondLine.visibility =
                if (row.subtitle.isEmpty() && row.time.isEmpty()) View.GONE else View.VISIBLE

            if (row.progress > 0f) {
                progress.visibility = View.VISIBLE
                progress.progress = (row.progress * 1000).toInt()
            } else {
                progress.visibility = View.GONE
            }

            val ctx = itemView.context
            val live = isSelected && !parked
            root.setBackgroundColor(if (isSelected) ctx.getColor(selectionColor()) else 0)
            title.setTextColor(ctx.getColor(if (live) R.color.ff_ground else R.color.ff_text))
            // The dim colours have to flip too. Amber-on-amber for the subtitle
            // was the first version and the selected row's second line simply
            // vanished.
            val dim = ctx.getColor(if (live) R.color.ff_ground else R.color.ff_text_dim)
            subtitle.setTextColor(dim)
            trailing.setTextColor(dim)
            time.setTextColor(dim)
        }

        /**
         * A group caption. Amber, small, tight, and never highlighted -- it has
         * to read as a label rather than as something you could press, on a
         * screen where the only affordance is the selection bar.
         */
        private fun bindHeader(row: Row) {
            val ctx = itemView.context
            root.setBackgroundColor(0)
            root.setPadding(dp(8), dp(6), dp(8), dp(1))
            oneLine()
            title.text = row.title
            title.textSize = 9f
            title.setTextColor(ctx.getColor(R.color.ff_amber))
            trailing.text = ""
            secondLine.visibility = View.GONE
            progress.visibility = View.GONE
        }

        /**
         * A paragraph. The one place in this app where text wraps.
         *
         * `maxLines` has to be reset explicitly, not just left alone: row_item
         * pins the title to one line with an ellipsis, and a recycled holder
         * would give a four-line summary rendered as "A young programmer at a…".
         */
        private fun bindBlurb(row: Row) {
            val ctx = itemView.context
            root.setBackgroundColor(0)
            root.setPadding(dp(8), dp(3), dp(8), dp(5))
            title.text = row.title
            title.textSize = 10f
            title.maxLines = Int.MAX_VALUE
            title.ellipsize = null
            title.setTextColor(ctx.getColor(R.color.ff_text_dim))
            trailing.text = ""
            secondLine.visibility = View.GONE
            progress.visibility = View.GONE
        }

        /**
         * The thin "more" button. Every field the full row sets has to be reset
         * here, because these holders are recycled -- an unreset 13sp title or a
         * leftover progress bar from a scrolled-past row would land in it.
         */
        private fun bindThin(row: Row, isSelected: Boolean) {
            val ctx = itemView.context
            root.setPadding(dp(8), dp(1), dp(8), dp(2))
            oneLine()
            title.text = row.title
            title.textSize = 9f
            subtitle.text = ""
            trailing.text = ""
            time.text = ""
            secondLine.visibility = View.GONE
            progress.visibility = View.GONE
            root.setBackgroundColor(if (isSelected) ctx.getColor(selectionColor()) else 0)
            title.setTextColor(
                ctx.getColor(if (isSelected && !parked) R.color.ff_ground else R.color.ff_amber)
            )
        }

        /** Undo what [bindBlurb] did, on a holder that may have been one. */
        private fun oneLine() {
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.END
        }

        private fun selectionColor(): Int =
            if (parked) R.color.ff_amber_parked else R.color.ff_amber

        private fun dp(v: Int): Int =
            (v * itemView.resources.displayMetrics.density).toInt()
    }
}
