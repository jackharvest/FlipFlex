package io.github.jackharvest.flipflex.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.jackharvest.flipflex.R

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
    )

    var rows: List<Row> = emptyList()
        private set

    var selected: Int = 0
        private set

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
        selected = if (keepSelection) previous.coerceIn(0, (newRows.size - 1).coerceAtLeast(0)) else 0
        adapter.notifyDataSetChanged()
        if (newRows.isNotEmpty()) {
            scrollToPosition(selected)
            onMove?.invoke(selected, newRows[selected])
        }
    }

    fun move(delta: Int): Boolean {
        if (rows.isEmpty()) return false
        val next = (selected + delta).coerceIn(0, rows.size - 1)
        if (next == selected) return false
        val was = selected
        selected = next
        adapter.notifyItemChanged(was)
        adapter.notifyItemChanged(next)
        // scrollToPosition, not smoothScroll: on a 2.4" list the animation is
        // longer than the gap between two D-pad presses when someone holds the
        // key down, and the list visibly lags behind the cursor.
        (layoutManager as LinearLayoutManager).scrollToPosition(next)
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
            root.setBackgroundColor(if (isSelected) ctx.getColor(R.color.ff_amber) else 0)
            title.setTextColor(ctx.getColor(if (isSelected) R.color.ff_ground else R.color.ff_text))
            // The dim colours have to flip too. Amber-on-amber for the subtitle
            // was the first version and the selected row's second line simply
            // vanished.
            val dim = ctx.getColor(if (isSelected) R.color.ff_ground else R.color.ff_text_dim)
            subtitle.setTextColor(dim)
            trailing.setTextColor(dim)
            time.setTextColor(dim)
        }
    }
}
