package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.github.jackharvest.flipflex.R

/**
 * The strip of view names across the top of a screen: `Recommended · Library ·
 * Categories`, or Settings' four groups.
 *
 * ## Why this exists twice over
 *
 * It started as three fixed TextViews in `browse_body.xml`, one per library
 * view, sharing the width in thirds. Settings then needed the same thing for a
 * different reason -- see [SettingsActivity] -- with four entries rather than
 * three, and a fixed set of thirds cannot do that.
 *
 * ## Why it scrolls rather than shrinking
 *
 * Four names in 240dp at 9sp is already tight, and the number of tabs is a
 * property of the screen rather than a constant. Dividing the width by however
 * many there are ends in three ellipsised words that all read "Recomm…", which
 * is worse than not labelling them at all. So the tabs keep their natural width
 * and the strip scrolls: the one you are on is always fully drawn and centred,
 * and the others run off both edges as a promise that there is more that way.
 * On a 240dp panel that is the same affordance a phone's tab bar has always had.
 *
 * The scroll is never driven directly -- there is no gesture on this handset to
 * drive it with. It follows the cursor, which is the only thing that moves.
 */
class TabStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Which tab the screen is actually showing. Painted amber even unfocused. */
    var active: Int = 0
        private set

    /** Where the cursor is while [focused]. Not the same thing as [active]. */
    var cursor: Int = 0
        private set

    /**
     * True while the strip owns the cursor rather than the list below it.
     *
     * Two amber bars is two cursors -- the same problem [RowList.parked] exists
     * for. A focused tab is filled; the active-but-unfocused one is amber text
     * on the ground colour, which says "you are here" without claiming that OK
     * would act on it.
     */
    var focused: Boolean = false
        private set

    val count: Int get() = row.childCount

    init {
        addView(row)
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // Nothing here is touch-driven, and a strip that can be flung out of
        // step with the cursor is a strip that lies about where you are.
        isFocusable = false
    }

    fun setTabs(labels: List<String>) {
        row.removeAllViews()
        labels.forEach { label ->
            row.addView(
                TextView(context).apply {
                    text = label
                    textSize = 9f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setPadding(dp(7), dp(1), dp(7), dp(1))
                }
            )
        }
        paint()
    }

    /** Say which tab the screen is on, without moving the cursor onto the strip. */
    fun setActive(index: Int) {
        active = index
        if (!focused) cursor = index
        paint()
        reveal(index)
    }

    /**
     * Take or release the cursor. Entering always starts on the active tab --
     * wherever the cursor was left last time is not where the user is now.
     */
    fun setFocused(on: Boolean) {
        focused = on
        if (on) cursor = active
        paint()
        reveal(cursor)
    }

    /** Returns true if the cursor moved, so a screen can consume the key. */
    fun move(delta: Int): Boolean {
        if (count == 0) return false
        val next = (cursor + delta).coerceIn(0, count - 1)
        if (next == cursor) return false
        cursor = next
        paint()
        reveal(cursor)
        return true
    }

    private fun paint() {
        for (i in 0 until count) {
            val tv = row.getChildAt(i) as TextView
            val on = focused && i == cursor
            tv.setBackgroundColor(if (on) context.getColor(R.color.ff_amber) else 0)
            tv.setTextColor(
                context.getColor(
                    when {
                        on -> R.color.ff_ground
                        i == active -> R.color.ff_amber
                        else -> R.color.ff_text_dim
                    }
                )
            )
        }
    }

    /**
     * Bring a tab into view, centred where the strip is wide enough to centre it.
     *
     * Posted rather than called directly because the usual caller is a screen
     * being built, where nothing has been measured yet and `getWidth()` is zero
     * -- which would scroll everything to the left edge and leave the last tab
     * off screen on arrival.
     */
    private fun reveal(index: Int) {
        val child = row.getChildAt(index) ?: return
        post {
            val target = child.left - (width - child.width) / 2
            scrollTo(target.coerceAtLeast(0), 0)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
