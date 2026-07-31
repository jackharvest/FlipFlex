package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.github.jackharvest.flipflex.R

/**
 * What the file is, an arrow, and what you are about to watch.
 *
 * ## Why an arrow and not a longer sentence
 *
 * The details page already showed both sets of facts as badges, in two colours
 * that mean "the file" and "what plays" -- and people still read them as one
 * row of seven coloured chips. The colours said which was which; nothing said
 * that the second was *caused by* the first. On a 240dp screen an arrow between
 * two columns says it in eleven pixels, and it says it to somebody who has never
 * read the colour key and never will.
 *
 * ## Why the audio is on it
 *
 * Because it is often the biggest change and it was the one nobody was told
 * about. An 8-channel E-AC3 film arrives here as 2-channel AAC on every path we
 * can ask the server for. Somebody who plugs headphones in and wonders where
 * the surround went deserves to have been able to see that before pressing play.
 *
 * ## Layout
 *
 * Two weighted columns with a fixed arrow between them, each column a caption
 * over a [BadgeStrip]. The badges wrap inside their own column, so a long
 * "5.5 Mbps" takes a second line rather than pushing the arrow off centre.
 * Nothing here measures itself: the weights do it, which is the difference
 * between this and BadgeStrip's own custom pass.
 */
class CompareStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** One side of the arrow. */
    data class Side(val caption: String, val badges: List<BadgeStrip.Badge>)

    private val leftCaption = caption()
    private val rightCaption = caption()
    private val leftBadges = BadgeStrip(context)
    private val rightBadges = BadgeStrip(context)

    private val arrow = TextView(context).apply {
        text = "→"
        textSize = 13f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.ff_text_dim))
        setPadding(dp(3), 0, dp(3), 0)
    }

    init {
        orientation = HORIZONTAL
        // The arrow sits against the captions rather than in the middle of the
        // two columns' full height: the columns are not the same height, and an
        // arrow centred between a two-badge column and a four-badge one points
        // at nothing on either side.
        gravity = Gravity.TOP
        isBaselineAligned = false

        addView(column(leftCaption, leftBadges), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(arrow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        addView(column(rightCaption, rightBadges), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    fun setSides(left: Side, right: Side) {
        leftCaption.text = left.caption.uppercase()
        rightCaption.text = right.caption.uppercase()
        leftBadges.setBadges(left.badges)
        rightBadges.setBadges(right.badges)
    }

    private fun column(cap: TextView, badges: BadgeStrip): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            addView(cap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(badges, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

    private fun caption(): TextView = TextView(context).apply {
        textSize = 8f
        maxLines = 1
        includeFontPadding = false
        setPadding(0, 0, 0, dp(2))
        setTextColor(context.getColor(R.color.ff_text_dim))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
