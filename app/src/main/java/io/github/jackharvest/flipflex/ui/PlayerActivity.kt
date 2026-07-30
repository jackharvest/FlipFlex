package io.github.jackharvest.flipflex.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.jackharvest.flipflex.R
import io.github.jackharvest.flipflex.input.Action
import io.github.jackharvest.flipflex.plex.PlexClient
import io.github.jackharvest.flipflex.plex.PlexPlayback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Playback, and telling Plex about it.
 *
 * ## Pause on close, and deliberately no resume on open
 *
 * Closing the lid backgrounds the app -- `CLAMSHELL` never reaches an app on
 * this handset, the framework keeps it for screen on/off, which is measured in
 * docs/keymap.md. That is not a problem, because the *consequence* is
 * guaranteed: `onPause` and `onStop` fire. So pause-on-close needs no root and
 * no `/dev/input` reading; it falls out of the ordinary lifecycle.
 *
 * The reverse is not implemented on purpose. A media app that resumes playing
 * the moment the phone is opened is a liability -- in a meeting, on a bus, in a
 * quiet room -- and the thing that actually matters is that Plex knows the
 * position, so resume is correctly staged wherever you next pick it up. Opening
 * the lid returns you to a paused player showing exactly where you were.
 */
class PlayerActivity : FlipActivity() {

    companion object {
        private const val TAG = "FlipFlex/player"

        private const val EXTRA_KEY = "ratingKey"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_START = "startMs"

        /** How often we tell the server where we are, while playing. */
        private const val TIMELINE_INTERVAL_MS = 10_000L

        private const val SEEK_MS = 15_000L

        fun intent(
            ctx: Context,
            ratingKey: String,
            title: String,
            subtitle: String,
            startMs: Long,
        ): Intent = Intent(ctx, PlayerActivity::class.java)
            .putExtra(EXTRA_KEY, ratingKey)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SUBTITLE, subtitle)
            .putExtra(EXTRA_START, startMs)
    }

    private lateinit var playerView: PlayerView
    private lateinit var positionView: TextView
    private lateinit var durationView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var stateView: TextView

    private var player: ExoPlayer? = null
    private var ticker: Job? = null

    private lateinit var ratingKey: String
    private var startMs = 0L
    private val session = PlexPlayback.newSession()

    /** Set once the transcode is actually running, so we know to tear it down. */
    private var sessionStarted = false

    private val uri get() = store.serverUri
    private val token get() = store.serverToken

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ratingKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        startMs = intent.getLongExtra(EXTRA_START, 0L)

        val body = layoutInflater.inflate(R.layout.activity_player, null)
        setBody(body)
        playerView = body.findViewById(R.id.player_view)
        positionView = body.findViewById(R.id.player_position)
        durationView = body.findViewById(R.id.player_duration)
        progressView = body.findViewById(R.id.player_progress)
        stateView = body.findViewById(R.id.player_state)

        body.findViewById<TextView>(R.id.player_title).text =
            intent.getStringExtra(EXTRA_TITLE).orEmpty()
        body.findViewById<TextView>(R.id.player_subtitle).text =
            intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()

        setHeader("Now Playing")
        setSoftKeys(left = getString(R.string.soft_home), right = getString(R.string.soft_options))

        // Without this the panel blanks mid-episode on the stock timeout, and
        // on a device where the lid is the real screen control that reads as a
        // crash. Cleared automatically when the activity goes away.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        startPlayback()
    }

    private fun startPlayback() {
        val u = uri
        val t = token
        if (u == null || t == null || ratingKey.isEmpty()) {
            showMessage(getString(R.string.msg_no_server))
            return
        }

        val url = PlexPlayback.streamUrl(u, t, ratingKey, session, startMs / 1000)
        stateView.text = getString(R.string.msg_loading)

        lifecycleScope.launch {
            // Preflight before handing the screen over. Plex answers a bare 400
            // when its transcoder is briefly wedged and recovers within a few
            // seconds; without this the user sees a black rectangle and a bounce
            // back to the list, which is indistinguishable from a broken app.
            val code = PlexPlayback.preflight(url, t)
            if (code != 200) {
                showMessage("Server would not start the stream.\nHTTP $code")
                stateView.text = ""
                return@launch
            }
            sessionStarted = true
            attachPlayer(url)
        }
    }

    private fun attachPlayer(url: String) {
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo

        // handleAudioFocus=true is what makes an incoming call, an alarm or
        // another app duck or stop us instead of two things playing at once.
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        // Pulling the headphones out, or a Bluetooth headset dropping, must not
        // switch the audio to the loudspeaker mid-episode. Both the jack and
        // Bluetooth are worth having on this handset, and both are exactly the
        // case this flag exists for.
        exo.setHandleAudioBecomingNoisy(true)

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> stateView.text = "Buffering…"
                    Player.STATE_READY -> paint()
                    Player.STATE_ENDED -> {
                        report("stopped", exo.duration.coerceAtLeast(0))
                        finish()
                    }
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = paint()

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "playback failed: ${error.errorCodeName}", error)
                showMessage("Playback failed.\n${error.errorCodeName}")
                stateView.text = ""
            }
        })

        // The token is already in the query string, so no custom DataSource
        // factory is needed here -- see PlexPlayback.params for why identity is
        // carried that way rather than in headers.
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
        startTicker()
    }

    // ---- progress and reporting -------------------------------------------

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            var sinceReport = 0L
            while (isActive) {
                paint()
                val p = player
                if (p != null && p.isPlaying) {
                    sinceReport += 500
                    if (sinceReport >= TIMELINE_INTERVAL_MS) {
                        sinceReport = 0
                        report("playing", p.currentPosition)
                    }
                }
                delay(500)
            }
        }
    }

    private fun paint() {
        val p = player ?: return
        val pos = p.currentPosition.coerceAtLeast(0)
        val dur = p.duration.let { if (it == C.TIME_UNSET) 0 else it }

        positionView.text = clock(pos)
        durationView.text = if (dur > 0) clock(dur) else ""
        progressView.progress = if (dur > 0) ((pos.toFloat() / dur) * 1000).toInt() else 0
        stateView.text = if (p.isPlaying) "❚❚  Pause" else "▶  Play"
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Fire-and-forget progress report. */
    private fun report(state: String, positionMs: Long) {
        val u = uri ?: return
        val t = token ?: return
        val dur = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
        lifecycleScope.launch {
            PlexPlayback.timeline(u, t, ratingKey, state, positionMs, dur)
        }
    }

    // ---- keys --------------------------------------------------------------

    override fun optionsHeading(): String = intent.getStringExtra(EXTRA_TITLE).orEmpty()

    override fun optionsFor(): List<Option> = listOf(
        Option("Restart from beginning") {
            player?.seekTo(0)
            player?.play()
        },
        Option("Stop") { finish() },
    )

    override fun onAction(action: Action, keyCode: Int): Boolean {
        val p = player ?: return false
        return when (action) {
            Action.SELECT -> {
                if (p.isPlaying) {
                    p.pause()
                    report("paused", p.currentPosition)
                } else {
                    p.play()
                    report("playing", p.currentPosition)
                }
                true
            }
            Action.LEFT -> { p.seekTo((p.currentPosition - SEEK_MS).coerceAtLeast(0)); true }
            Action.RIGHT -> { p.seekTo(p.currentPosition + SEEK_MS); true }
            // Up/down are free here. Left unbound rather than mapped to volume,
            // which the hardware volume rocker already does properly.
            else -> false
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    /**
     * Lid closed, or the app backgrounded any other way.
     *
     * Pause first, report second: the report reads the position, and pausing
     * first means the number we send is the one the user will see when they
     * open the lid again.
     */
    override fun onPause() {
        super.onPause()
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            report("paused", p.currentPosition)
        }
    }

    override fun onDestroy() {
        ticker?.cancel()
        val p = player
        val position = p?.currentPosition ?: 0L
        val dur = p?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
        p?.release()
        player = null

        val u = uri
        val t = token
        if (u != null && t != null) {
            // runBlocking, which is normally the wrong tool -- but lifecycleScope
            // is cancelled the moment onDestroy returns, so a launch{} here would
            // be killed before the request left the device. These two calls are
            // exactly what makes resume work and what stops an orphaned ffmpeg
            // process on the server, so they are worth blocking for. Both have
            // short timeouts, and this runs on a screen that is already gone.
            runBlocking {
                PlexPlayback.timeline(u, t, ratingKey, "stopped", position, dur)
                if (sessionStarted) PlexPlayback.stop(u, t, session)
            }
        }
        super.onDestroy()
    }
}
