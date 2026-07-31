package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import com.github.jackharvest.flipflex.R

/**
 * The little coloured facts: `1080p` `HEVC` `5.5 Mbps` `53 MB`.
 *
 * ## Why these are not a line of text
 *
 * They were, and the line read `1080p · HEVC · 5.5 Mbps → 320x240 800 kbps` in
 * the same dim grey as the summary directly beneath it. At 10sp on a 240dp
 * panel that is thirty characters of undifferentiated prose saying four
 * separate things, none of which you can find by glancing -- which is the only
 * way anybody reads a details page. Colour does the separating that punctuation
 * was failing to do, and it does it without costing a second line.
 *
 * ## Why a custom ViewGroup rather than nested LinearLayouts
 *
 * Because the number of badges is not known in advance -- an item with no
 * bitrate reported has three, a downloaded episode with subtitles has five --
 * and they have to wrap onto a second line when they no longer fit. A
 * LinearLayout cannot wrap and a horizontal ScrollView would hide facts behind
 * a gesture this handset has no way to make. Measuring and laying out in rows
 * is twenty lines and is exactly what is needed.
 */
class BadgeStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** [color] is a colour resource; the text is always drawn on the ground colour. */
    data class Badge(val text: String, val color: Int)

    private val gapPx = dp(3)
    private val linePx = dp(2)

    fun setBadges(badges: List<Badge>) {
        removeAllViews()
        badges.forEach { b ->
            addView(
                TextView(context).apply {
                    text = b.text
                    textSize = 9f
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setTextColor(context.getColor(R.color.ff_badge_text))
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(3).toFloat()
                        setColor(context.getColor(b.color))
                    }
                }
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var x = 0
        var rowHeight = 0
        var height = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(unbounded, unbounded)
            // A badge wider than the strip still gets its own line rather than
            // being dropped: a truncated "5.5 Mbps" is worse than a wrapped one.
            if (x > 0 && x + child.measuredWidth > width) {
                height += rowHeight + linePx
                x = 0
                rowHeight = 0
            }
            x += child.measuredWidth + gapPx
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(width, height + rowHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x > 0 && x + child.measuredWidth > width) {
                y += rowHeight + linePx
                x = 0
                rowHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gapPx
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
