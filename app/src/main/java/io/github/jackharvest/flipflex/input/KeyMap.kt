package io.github.jackharvest.flipflex.input

import android.view.KeyEvent

/**
 * Every key this handset actually delivers to an app, and what FlipFlex does
 * with it.
 *
 * Written against the measured table in docs/keymap.md, not against the stock
 * `.kl` files. The two disagree in ways that matter: the `.kl` files advertise
 * MESSENGER, FAVORITE_CONTACTS, SPEAKER and QUICK_DIAL as available, and not one
 * of the four is usable -- three are bound above the app layer and the fourth is
 * not a physical button. Binding anything to them would fail silently.
 */
enum class Action {
    /** Move selection. */
    UP, DOWN, LEFT, RIGHT,

    /** Activate the focused row. The physical OK in the middle of the D-pad. */
    SELECT,

    /** Left softkey: always Back, matching the proof-of-concept art. */
    SOFT_LEFT,

    /** Right softkey: contextual -- Select, Options, whatever the screen needs. */
    SOFT_RIGHT,

    /** The dedicated back arrow next to `*`. An ordinary KEYCODE_BACK. */
    BACK,

    /** Digit 0-9. See [digitOf] for the value. */
    DIGIT,

    /** `*` and `#`. Unbound so far, but they arrive and are ours to use. */
    STAR, POUND,

    /** The green call key -- the only genuinely spare hardware button we have. */
    SPARE,

    /** Volume. Passed through to the system rather than handled. */
    VOLUME_UP, VOLUME_DOWN,
}

object KeyMap {

    /**
     * Translate a keycode, or null if this handset never delivers it.
     *
     * Null is the common case for keys that exist on the keypad: the email,
     * contacts and speaker buttons all produce nothing here, because the
     * framework acts on them before any app is consulted.
     */
    fun actionOf(keyCode: Int): Action? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Action.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Action.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Action.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Action.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER -> Action.SELECT
        KeyEvent.KEYCODE_SOFT_LEFT -> Action.SOFT_LEFT
        KeyEvent.KEYCODE_SOFT_RIGHT -> Action.SOFT_RIGHT
        KeyEvent.KEYCODE_BACK -> Action.BACK
        KeyEvent.KEYCODE_STAR -> Action.STAR
        KeyEvent.KEYCODE_POUND -> Action.POUND
        KeyEvent.KEYCODE_CALL -> Action.SPARE
        KeyEvent.KEYCODE_VOLUME_UP -> Action.VOLUME_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> Action.VOLUME_DOWN
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> Action.DIGIT
        else -> null
    }

    /** 0-9 for a [Action.DIGIT] keycode, else -1. */
    fun digitOf(keyCode: Int): Int =
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            keyCode - KeyEvent.KEYCODE_0
        } else {
            -1
        }

    /**
     * Volume is the one thing we deliberately do not consume -- the system's own
     * handling is what the user expects, and re-implementing it would only get
     * the ringer/media stream distinction wrong.
     */
    fun passThrough(action: Action?): Boolean =
        action == Action.VOLUME_UP || action == Action.VOLUME_DOWN

    /**
     * CALL must be caught in `dispatchKeyEvent`, before the framework routes it
     * to the dialer. Everything else is fine to handle in `onKeyDown`.
     */
    fun mustInterceptEarly(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_CALL
}
