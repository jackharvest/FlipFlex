package io.github.jackharvest.flipflex.probe

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.jackharvest.flipflex.databinding.ActivityProbeBinding
import java.io.File

/**
 * Phase 1: what does this handset actually deliver to an app?
 *
 * docs/keymap.md was read off the stock `.kl` files, so it says what the
 * framework *will map* a scancode to. It does not say whether the event ever
 * reaches us -- SOFT_LEFT/SOFT_RIGHT are frequently swallowed by the system on
 * OEM builds, BACK is normally consumed before an app sees it, and the four
 * spare hardware keys may be bound to stock apps. docs/keymap.md:79 is explicit:
 * do not write KeyMap.kt against that table without this pass.
 *
 * So this deliberately makes no assumptions and hardcodes no expected keycodes.
 * It records every distinct (keyCode, scanCode) pair that arrives, in the order
 * it arrives, and reports which callback saw it. The human presses the keys in a
 * known order; the mapping falls out of the sequence.
 */
class ProbeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProbeBinding

    /** Distinct (keyCode, scanCode) pairs, in first-seen order. */
    private val seen = LinkedHashMap<Pair<Int, Int>, Entry>()

    /** Last BACK press, so a double-tap can escape an intercepted BACK. */
    private var lastBackAt = 0L

    private data class Entry(
        val index: Int,
        val keyCode: Int,
        val scanCode: Int,
        val name: String,
        /** Callbacks that saw this key: D=dispatchKeyEvent, ↓=onKeyDown, ↑=onKeyUp. */
        val paths: MutableSet<String> = linkedSetOf(),
        var count: Int = 0,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        render()
    }

    // dispatchKeyEvent is the earliest hook an Activity has. A key that shows up
    // here but never in onKeyDown is one the framework routes elsewhere -- that
    // distinction is the whole reason both are instrumented rather than just one.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        record(event, "D")

        // Prove BACK is interceptable by consuming it, but keep an escape hatch:
        // two presses inside 1.5s exit. Without this, a successfully intercepted
        // BACK would leave no way out of the probe except adb.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 1_500) {
                dump()
                finish()
                return true
            }
            lastBackAt = now
            binding.hint.text = "BACK intercepted - press again to exit"
            return true
        }

        super.dispatchKeyEvent(event)
        // Always consume. Anything we pass through risks the framework acting on
        // it (volume UI, launcher shortcuts) and stealing focus mid-probe.
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        record(event, "↓")
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        record(event, "↑")
        return true
    }

    private fun record(event: KeyEvent, path: String) {
        val key = event.keyCode to event.scanCode
        val entry = seen.getOrPut(key) {
            Entry(
                index = seen.size + 1,
                keyCode = event.keyCode,
                scanCode = event.scanCode,
                // keyCodeToString returns "KEYCODE_FOO", or a bare number for
                // vendor codes with no AOSP name -- which is itself a finding
                // worth seeing, so it is shown verbatim.
                name = KeyEvent.keyCodeToString(event.keyCode),
            )
        }
        entry.paths += path
        if (event.action == KeyEvent.ACTION_DOWN) entry.count++

        Log.i(
            TAG,
            "key n=${entry.index} code=${event.keyCode} name=${entry.name} " +
                "scan=${event.scanCode} action=${event.action} path=$path " +
                "src=${event.source} dev=${event.deviceId} repeat=${event.repeatCount}",
        )
        render()
    }

    private fun render() {
        binding.header.text = "KEY PROBE  ${seen.size} distinct"
        binding.log.text = seen.values.joinToString("\n") { e ->
            // Trim the KEYCODE_ prefix: on a 240dp-wide panel it costs half the
            // line and carries no information.
            val short = e.name.removePrefix("KEYCODE_")
            "%2d %-18s %3d sc=%-4d %s".format(
                e.index, short.take(18), e.keyCode, e.scanCode, e.paths.joinToString(""),
            )
        }
        binding.scroll.post { binding.scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /**
     * Write the table where `adb pull` can reach it, so docs/keymap.md can be
     * filled in from a file rather than by transcribing off a 240x320 screen.
     */
    private fun dump() {
        val text = buildString {
            appendLine("# FlipFlex keycode probe")
            appendLine("# n\tkeyCode\tname\tscanCode\tpaths\tdownCount")
            seen.values.forEach { e ->
                appendLine(
                    "${e.index}\t${e.keyCode}\t${e.name}\t${e.scanCode}\t" +
                        "${e.paths.joinToString("")}\t${e.count}",
                )
            }
        }
        runCatching {
            File(getExternalFilesDir(null), "keyprobe.tsv").writeText(text)
        }.onFailure { Log.w(TAG, "could not write keyprobe.tsv", it) }
        Log.i(TAG, "PROBE RESULT\n$text")
    }

    companion object {
        // Short tag: `adb logcat -s FlipFlexProbe:V` is the whole read path.
        private const val TAG = "FlipFlexProbe"
    }
}
