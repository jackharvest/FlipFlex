package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.github.jackharvest.flipflex.R
import kotlin.math.min

/**
 * The 4058G, drawn, with one control lit and a leader line to its name.
 *
 * ## Why this is a Canvas and not the reference PNG
 *
 * The artwork this is modelled on is 1024x1024. The content area on this
 * handset is 240x246, and an open flip phone is about one unit wide to three
 * and a half tall -- so scaled to fit, the whole phone is barely seventy pixels
 * across and the keypad is mush. Drawing it instead buys three things a bitmap
 * cannot:
 *
 * - **Only the part that matters is drawn to scale.** The upper shell is
 *   truncated at the top rather than reproduced, because the screen is not a
 *   control; the keypad gets the height that would have gone to it. This is the
 *   crop in the reference art, made a property of the drawing.
 * - **A button can be lit.** Highlighting a key on a bitmap means hardcoding its
 *   pixel rectangle anyway, and then the rectangle is wrong the moment the image
 *   is scaled by a different amount.
 * - **It costs nothing.** A 1024x1024 ARGB bitmap is 4 MB on a device whose
 *   `dalvik.vm.heapgrowthlimit` is 128 MB, to show a line drawing.
 *
 * Everything is laid out in a fixed [U_W] x [U_H] unit box taken off the
 * reference art's proportions, then scaled once per draw. Nothing here is in
 * pixels except the stroke widths and the type.
 */
class PhoneDiagram @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /**
     * Something on the handset that can be pointed at.
     *
     * The D-pad ring appears twice on purpose. Up/down and left/right are one
     * piece of plastic and two completely different jobs -- moving the cursor
     * and paging or seeking -- and a tour that lit the whole ring for both would
     * be showing the same picture twice while claiming to teach two things.
     */
    enum class Part {
        SCREEN_LABEL_L,
        SCREEN_LABEL_R,
        SOFT_LEFT,
        SOFT_RIGHT,
        DPAD_UD,
        DPAD_LR,
        OK,
        CALL,
        END,
        FAV,
        MSG,
        BACK,
        DIGITS,
        STAR,
        POUND,
        HINGE,
        VOLUME,
    }

    private var highlight: Set<Part> = emptySet()
    private var anchor: Part? = null
    private var label: String = ""
    private var labelOnRight: Boolean = true

    fun show(parts: Set<Part>, anchor: Part, label: String, onRight: Boolean) {
        this.highlight = parts
        this.anchor = anchor
        this.label = label
        this.labelOnRight = onRight
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val ground = context.getColor(R.color.ff_ground)
    private val amber = context.getColor(R.color.ff_amber)

    /**
     * The outline colour, and the glow behind it.
     *
     * Cool white over a violet halo, which is what the reference art is. The
     * violet is [R.color.ff_badge_local] rather than a fourth colour invented
     * here, so the diagram stays inside the palette the rest of the app uses.
     */
    private val ink = 0xFFDCDCEA.toInt()
    private val glowTint = context.getColor(R.color.ff_badge_local)
    private val callTint = context.getColor(R.color.ff_key_call)
    private val endTint = context.getColor(R.color.ff_key_end)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(8.5f)
        isFakeBoldText = true
    }
    private val fade = Paint()

    // Set once per draw, and read by the leader-line maths afterwards.
    private var scale = 1f
    private var originX = 0f
    private var originY = 0f

    private fun tx(ux: Float) = originX + ux * scale
    private fun ty(uy: Float) = originY + uy * scale

    /** A unit rectangle in view pixels. */
    private fun px(r: RectF) = RectF(tx(r.left), ty(r.top), tx(r.right), ty(r.bottom))

    override fun onDraw(canvas: Canvas) {
        // Height drives the scale, because the phone is far taller than it is
        // wide and the content area is not. The width cap only stops the
        // drawing from eating the gutters the labels live in.
        val availH = height - dp(6f)
        val availW = min(width * 0.40f, dp(104f))
        scale = min(availH / VIS_H, availW / U_W)
        originX = width / 2f - (U_W / 2f) * scale
        originY = dp(3f) - VIS_TOP * scale

        glyph.textSize = 22f * scale

        drawShell(canvas)
        drawScreen(canvas)
        drawSoftKeys(canvas)
        drawDpad(canvas)
        drawCallKeys(canvas)
        drawKeypad(canvas)
        drawFades(canvas)
        drawLeader(canvas)
    }

    // ---- the handset -------------------------------------------------------

    private fun hot(part: Part) = part in highlight

    /**
     * Set both paints for an outline that is either lit or not.
     *
     * The halo is deliberately narrow and very faint. The first version used
     * 2.6dp at alpha 40 behind a 1dp line and the whole handset came out
     * violet: at density 1.0 the halo is nearly three times the width of the
     * line it is supposed to be sitting behind, so it *is* the drawing. Twice
     * the width at a sixth of the opacity reads as a glow instead of as a
     * colour.
     */
    private fun stroke(on: Boolean, tint: Int = ink) {
        glow.color = if (on) amber else if (tint == ink) glowTint else tint
        glow.alpha = if (on) 70 else 26
        glow.strokeWidth = dp(if (on) 3f else 2f)
        line.color = if (on) amber else tint
        line.alpha = if (on) 255 else 205
        line.strokeWidth = dp(if (on) 1.4f else 1f)
    }

    /** Stroke a unit rectangle, with the halo behind it. */
    private fun rr(canvas: Canvas, r: RectF, radius: Float, on: Boolean, tint: Int = ink) {
        val p = px(r)
        val rad = radius * scale
        stroke(on, tint)
        canvas.drawRoundRect(p, rad, rad, glow)
        canvas.drawRoundRect(p, rad, rad, line)
    }

    private fun circle(canvas: Canvas, cx: Float, cy: Float, r: Float, on: Boolean) {
        val x = tx(cx)
        val y = ty(cy)
        val rad = r * scale
        stroke(on)
        canvas.drawCircle(x, y, rad, glow)
        canvas.drawCircle(x, y, rad, line)
    }

    /**
     * The two shells and the hinge.
     *
     * The upper shell's rectangle starts above the visible box and is simply
     * clipped by it, which is what gives the cropped-and-faded look of the
     * reference art without any masking work -- [drawFades] then dissolves the
     * cut edge into the background.
     */
    private fun drawShell(canvas: Canvas) {
        rr(canvas, UPPER, 16f, false)
        rr(canvas, LOWER, 16f, false)
        rr(canvas, HINGE, 4f, hot(Part.HINGE))
        rr(canvas, VOLUME, 2f, hot(Part.VOLUME))
    }

    /**
     * The bottom of the screen, and the two labels that sit on it.
     *
     * Drawn as two short filled bars rather than as words. At this scale the
     * strip is about sixty pixels wide, which is not enough for legible text --
     * and the shape is the point anyway: a label at each end, directly above
     * the key it belongs to.
     */
    private fun drawScreen(canvas: Canvas) {
        rr(canvas, SCREEN, 5f, false)
        val strip = px(SCREEN_LABELS)
        val barW = strip.width() * 0.26f
        val barH = dp(2.4f)
        val y = strip.centerY() - barH / 2f
        // One bar per soft key, lit independently. The step about the left key
        // lights the left label, which is the fact being taught: the word above
        // that key belongs to that key.
        bar(canvas, strip.left, y, barW, barH, hot(Part.SCREEN_LABEL_L))
        bar(canvas, strip.right - barW, y, barW, barH, hot(Part.SCREEN_LABEL_R))
    }

    private fun bar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, on: Boolean) {
        fill.color = if (on) amber else ink
        fill.alpha = if (on) 255 else 150
        canvas.drawRect(x, y, x + w, y + h, fill)
    }

    private fun drawSoftKeys(canvas: Canvas) {
        rr(canvas, SOFT_LEFT, 3f, hot(Part.SOFT_LEFT))
        rr(canvas, SOFT_RIGHT, 3f, hot(Part.SOFT_RIGHT))
    }

    /**
     * The ring, its four arrows, and OK in the middle.
     *
     * **Only the half of the ring being taught lights up.** Lighting the whole
     * ring was the first attempt and it makes the two D-pad steps -- up/down
     * for moving, left/right for paging and seeking -- draw an identical
     * picture: one amber circle, twice, with nothing but a pair of small
     * triangles to tell them apart. Two arcs say which axis is meant at a
     * glance, which is the only reason a diagram beats a sentence here.
     */
    private fun drawDpad(canvas: Canvas) {
        val ud = hot(Part.DPAD_UD)
        val lr = hot(Part.DPAD_LR)
        circle(canvas, DPAD_CX, DPAD_CY, DPAD_R, false)
        if (ud || lr) {
            val box = px(RectF(DPAD_CX - DPAD_R, DPAD_CY - DPAD_R, DPAD_CX + DPAD_R, DPAD_CY + DPAD_R))
            stroke(true)
            val starts = if (ud) floatArrayOf(-125f, 55f) else floatArrayOf(-35f, 145f)
            starts.forEach {
                canvas.drawArc(box, it, 70f, false, glow)
                canvas.drawArc(box, it, 70f, false, line)
            }
        }
        circle(canvas, DPAD_CX, DPAD_CY, OK_R, hot(Part.OK))

        arrow(canvas, 0f, -1f, ud)
        arrow(canvas, 0f, 1f, ud)
        arrow(canvas, -1f, 0f, lr)
        arrow(canvas, 1f, 0f, lr)

        glyph.color = if (hot(Part.OK)) amber else ink
        glyph.alpha = if (hot(Part.OK)) 255 else 170
        glyph.textSize = 16f * scale
        canvas.drawText(
            "OK",
            tx(DPAD_CX),
            ty(DPAD_CY) + glyph.textSize * 0.36f,
            glyph,
        )
    }

    /** One D-pad arrow, as a filled triangle pointing along (dx, dy). */
    private fun arrow(canvas: Canvas, dx: Float, dy: Float, on: Boolean) {
        // Midway between the OK circle and the ring, which is the only band of
        // the pad that is empty.
        val mid = (OK_R + DPAD_R) / 2f
        val cx = tx(DPAD_CX + dx * mid)
        val cy = ty(DPAD_CY + dy * mid)
        val h = (if (on) 6.5f else 5f) * scale
        val w = (if (on) 5.5f else 4.5f) * scale
        val path = Path().apply {
            moveTo(cx + dx * h, cy + dy * h)
            lineTo(cx - dx * h + dy * w, cy - dy * h + dx * w)
            lineTo(cx - dx * h - dy * w, cy - dy * h - dx * w)
            close()
        }
        fill.color = if (on) amber else ink
        fill.alpha = if (on) 255 else 110
        canvas.drawPath(path, fill)
    }

    /**
     * The call and end keys, in their own colours.
     *
     * The step about search says "the green key", which is only a useful thing
     * to say if the reader can see which one is green. These are the only two
     * parts of the drawing that are not the ink colour, and the only two on the
     * real handset that are not grey.
     */
    private fun drawCallKeys(canvas: Canvas) {
        rr(canvas, CALL, 7f, hot(Part.CALL), tint = callTint)
        rr(canvas, END, 7f, hot(Part.END), tint = endTint)
    }

    /**
     * The four rows of keys, glyphs and all.
     *
     * The top row is favourites, messages and the back arrow, in that order --
     * which is the row that matters most on this handset, because two of the
     * three never reach an app at all and the third is the app's only Back.
     */
    private fun drawKeypad(canvas: Canvas) {
        val digitsOn = hot(Part.DIGITS)

        key(canvas, 0, 0, hot(Part.FAV)) { c, r, on -> star(c, r, on) }
        key(canvas, 1, 0, hot(Part.MSG)) { c, r, on -> envelope(c, r, on) }
        key(canvas, 2, 0, hot(Part.BACK)) { c, r, on -> backArrow(c, r, on) }

        for (row in 1..3) {
            for (col in 0..2) {
                val digit = (row - 1) * 3 + col + 1
                key(canvas, col, row, digitsOn) { c, r, on -> text(c, r, digit.toString(), on) }
            }
        }
        key(canvas, 0, 4, hot(Part.STAR)) { c, r, on -> text(c, r, "*", on, lift = 0.55f) }
        key(canvas, 1, 4, digitsOn) { c, r, on -> text(c, r, "0", on) }
        key(canvas, 2, 4, hot(Part.POUND)) { c, r, on -> text(c, r, "#", on) }
    }

    private fun key(
        canvas: Canvas,
        col: Int,
        row: Int,
        on: Boolean,
        glyphs: (Canvas, RectF, Boolean) -> Unit,
    ) {
        val r = keyRect(col, row)
        rr(canvas, r, 5f, on)
        glyphs(canvas, px(r), on)
    }

    /**
     * A character on a keycap.
     *
     * [lift] exists for the star. A keypad star sits low on its key and the
     * typographic asterisk sits high, so centring it the way a digit is centred
     * puts it against the top edge -- and at a key height of eleven pixels that
     * is the difference between a star and a speck.
     */
    private fun text(canvas: Canvas, r: RectF, s: String, on: Boolean, lift: Float = 0.36f) {
        glyph.color = if (on) amber else ink
        glyph.alpha = if (on) 255 else 195
        glyph.textSize = r.height() * 0.62f
        canvas.drawText(s, r.centerX(), r.centerY() + glyph.textSize * lift, glyph)
    }

    private fun glyphStroke(on: Boolean) {
        line.color = if (on) amber else ink
        line.alpha = if (on) 255 else 200
        line.strokeWidth = dp(0.9f)
    }

    private fun star(canvas: Canvas, r: RectF, on: Boolean) {
        val rad = r.height() * 0.34f
        val path = Path()
        for (i in 0 until 10) {
            val a = (-Math.PI / 2 + i * Math.PI / 5).toFloat()
            val d = if (i % 2 == 0) rad else rad * 0.45f
            val x = r.centerX() + Math.cos(a.toDouble()).toFloat() * d
            val y = r.centerY() + Math.sin(a.toDouble()).toFloat() * d
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        glyphStroke(on)
        canvas.drawPath(path, line)
    }

    private fun envelope(canvas: Canvas, r: RectF, on: Boolean) {
        val w = r.height() * 0.42f
        val h = r.height() * 0.28f
        val box = RectF(r.centerX() - w, r.centerY() - h, r.centerX() + w, r.centerY() + h)
        glyphStroke(on)
        canvas.drawRect(box, line)
        canvas.drawLine(box.left, box.top, box.centerX(), box.centerY(), line)
        canvas.drawLine(box.right, box.top, box.centerX(), box.centerY(), line)
    }

    /**
     * The back arrow, drawn rather than typed.
     *
     * The character that looks right here is U+21B0, and a font that does not
     * carry it renders a tofu box on a diagram whose entire job is to say which
     * key is which. Three strokes are cheaper than that risk.
     */
    private fun backArrow(canvas: Canvas, r: RectF, on: Boolean) {
        val w = r.height() * 0.40f
        val h = r.height() * 0.28f
        val cx = r.centerX()
        val cy = r.centerY()
        glyphStroke(on)
        canvas.drawLine(cx - w, cy + h * 0.4f, cx + w, cy + h * 0.4f, line)
        canvas.drawLine(cx + w, cy + h * 0.4f, cx + w, cy - h, line)
        canvas.drawLine(cx - w, cy + h * 0.4f, cx - w * 0.2f, cy - h * 0.5f, line)
        canvas.drawLine(cx - w, cy + h * 0.4f, cx - w * 0.2f, cy + h * 1.3f, line)
    }

    // ---- the crop, and the label -------------------------------------------

    /**
     * Dissolve the top and bottom edges into the background.
     *
     * The upper shell is cut off by the visible box rather than ending, and a
     * hard horizontal cut across a line drawing reads as a rendering fault. A
     * gradient down to the ground colour is the third of the reference images,
     * and it costs two rectangles.
     */
    private fun drawFades(canvas: Canvas) {
        val top = dp(16f)
        fade.shader = LinearGradient(0f, 0f, 0f, top, ground, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), top, fade)

        val bot = dp(10f)
        fade.shader = LinearGradient(
            0f, height - bot, 0f, height.toFloat(), 0x00000000, ground, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, height - bot, width.toFloat(), height.toFloat(), fade)
        fade.shader = null
    }

    /**
     * A thin line from the lit control out to its name.
     *
     * The label goes in whichever gutter is nearer the control, which is why
     * the phone is centred rather than pushed to one side: a leader from the
     * left soft key to a label on the right would cross the D-pad, the call
     * keys and half the keypad to get there.
     *
     * The segment still inside the shell is drawn faint. It has to exist -- the
     * line has to start at the button, or it is pointing at nothing in
     * particular -- but at full strength it looks like a scratch across the
     * drawing.
     */
    private fun drawLeader(canvas: Canvas) {
        val part = anchor ?: return
        if (label.isEmpty()) return

        val r = px(anchorRect(part))
        val dir = if (labelOnRight) 1f else -1f
        val startX = if (labelOnRight) r.right else r.left
        val y = r.centerY()
        val shellX = if (labelOnRight) tx(LOWER.right) else tx(LOWER.left)
        val outX = shellX + dir * dp(7f)

        // The label sits a little above the line's own level so the text never
        // lands on top of it. Clamped, because the top row of keys is close
        // enough to the ceiling for the lift to push it off screen.
        val ceiling = labelPaint.textSize + dp(2f)
        val floor = (height - dp(4f)).coerceAtLeast(ceiling)
        val labelY = (y - dp(4f)).coerceIn(ceiling, floor)

        line.color = amber
        line.strokeWidth = dp(1f)

        line.alpha = 70
        canvas.drawLine(startX, y, shellX, y, line)

        line.alpha = 255
        val path = Path().apply {
            moveTo(shellX, y)
            lineTo(outX, y)
            lineTo(outX + dir * dp(5f), labelY)
        }
        canvas.drawPath(path, line)

        val textX = outX + dir * dp(7f)
        labelPaint.color = amber
        labelPaint.textAlign = if (labelOnRight) Paint.Align.LEFT else Paint.Align.RIGHT
        canvas.drawText(label, textX, labelY - dp(1.5f), labelPaint)

        // Underscore the name so it reads as the end of the line rather than as
        // text that happens to be nearby.
        val w = labelPaint.measureText(label)
        line.alpha = 160
        canvas.drawLine(textX, labelY, textX + dir * w, labelY, line)

        // A dot on the control itself. On the keypad the amber outline is
        // unmistakable, but on the ring -- which is lit for two different
        // steps -- the dot is what says which side of it is being talked about.
        fill.color = amber
        fill.alpha = 255
        canvas.drawCircle(startX, y, dp(1.6f), fill)
    }

    /** Where the leader line starts, in unit space. */
    private fun anchorRect(part: Part): RectF = when (part) {
        Part.SCREEN_LABEL_L, Part.SCREEN_LABEL_R -> SCREEN_LABELS
        Part.SOFT_LEFT -> SOFT_LEFT
        Part.SOFT_RIGHT -> SOFT_RIGHT
        Part.DPAD_UD, Part.DPAD_LR, Part.OK ->
            RectF(DPAD_CX - DPAD_R, DPAD_CY - DPAD_R, DPAD_CX + DPAD_R, DPAD_CY + DPAD_R)
        Part.CALL -> CALL
        Part.END -> END
        Part.FAV -> keyRect(0, 0)
        Part.MSG -> keyRect(1, 0)
        Part.BACK -> keyRect(2, 0)
        // The middle of the block of nine, so the line comes out of the keypad
        // rather than off one corner of it.
        Part.DIGITS -> keyRect(1, 2)
        Part.STAR -> keyRect(0, 4)
        Part.POUND -> keyRect(2, 4)
        Part.HINGE -> HINGE
        Part.VOLUME -> VOLUME
    }

    private companion object {
        /**
         * The unit box, in the proportions of the reference art.
         *
         * [VIS_TOP] is above the origin on purpose: the upper shell and the
         * screen are defined as if the whole open phone were drawn, and then
         * only the last sliver of them is inside the visible box. That is the
         * crop, expressed as geometry instead of as a second set of numbers.
         */
        const val U_W = 260f
        const val U_H = 486f
        const val VIS_TOP = -10f
        const val VIS_H = U_H - VIS_TOP

        val UPPER = RectF(6f, -70f, 254f, 56f)
        val SCREEN = RectF(26f, -70f, 234f, 36f)
        val SCREEN_LABELS = RectF(30f, 20f, 230f, 34f)
        val HINGE = RectF(78f, 52f, 182f, 86f)
        val LOWER = RectF(6f, 58f, 254f, 486f)
        val VOLUME = RectF(252f, 132f, 260f, 180f)

        val SOFT_LEFT = RectF(40f, 128f, 78f, 142f)
        val SOFT_RIGHT = RectF(182f, 128f, 220f, 142f)

        const val DPAD_CX = 130f
        const val DPAD_CY = 178f
        const val DPAD_R = 48f
        const val OK_R = 27f

        val CALL = RectF(38f, 200f, 74f, 220f)
        val END = RectF(186f, 200f, 222f, 220f)

        /** Key grid: three columns of 60, five rows of 36. */
        val KEY_COL_X = floatArrayOf(24f, 100f, 176f)
        val KEY_ROW_Y = floatArrayOf(240f, 288f, 336f, 384f, 432f)
        const val KEY_W = 60f
        const val KEY_H = 36f

        fun keyRect(col: Int, row: Int) = RectF(
            KEY_COL_X[col], KEY_ROW_Y[row], KEY_COL_X[col] + KEY_W, KEY_ROW_Y[row] + KEY_H,
        )
    }
}
