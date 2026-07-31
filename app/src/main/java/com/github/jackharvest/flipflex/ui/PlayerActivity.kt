package com.github.jackharvest.flipflex.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.dl.Downloads
import com.github.jackharvest.flipflex.input.Action
import com.github.jackharvest.flipflex.plex.PlexPlayback
import com.github.jackharvest.flipflex.plex.Quality
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

        /**
         * Not private, because [FlipActivity.startPlayback] reads it back off a
         * built intent to decide whether this playback would come over the
         * radio. Every screen in the app hands its player intent to that guard,
         * so the alternative is passing the ratingKey twice at six call sites
         * and having them disagree at one of them.
         */
        const val EXTRA_KEY = "ratingKey"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_START = "startMs"
        private const val EXTRA_SUBTITLES = "burnSubtitles"

        /** How often we tell the server where we are, while playing. */
        private const val TIMELINE_INTERVAL_MS = 10_000L

        /**
         * How often we tell the server we still exist, while *not* playing.
         *
         * Ten seconds is what Plex's own clients use. See [PlexPlayback.ping]
         * for what it prevents, which is the lid being shut for five minutes
         * and playback dying some minutes after it is opened again.
         */
        private const val PING_INTERVAL_MS = 10_000L

        /**
         * How many times a stream may be rebuilt under one playback before we
         * admit defeat and put an error on the screen.
         *
         * Three, because the failures worth recovering from are one-off: a
         * reaped session, a Wi-Fi handover, a transcoder that fell over and was
         * restarted. Anything that fails three times in a row is a server that
         * cannot serve this file, and retrying that forever would just be an
         * endless buffering spinner with no way for the user to learn why.
         */
        private const val MAX_RECOVERIES = 3

        /**
         * Playing this long without incident means the trouble is behind us and
         * the recovery budget is spent fairly on the *next* one.
         */
        private const val RECOVERY_FORGIVEN_MS = 60_000L

        private const val SEEK_MS = 15_000L

        /** How long the controls stay up after a key press. */
        private const val CHROME_MS = 3_500L

        /**
         * The three ways this screen can be turned. Values of
         * [com.github.jackharvest.flipflex.store.Store.playerOrientation].
         *
         * There are two landscapes and they are not interchangeable, because
         * this handset's edges are not symmetric. `SCREEN_ORIENTATION_LANDSCAPE`
         * puts the edge carrying the **power button and the headphone jack**
         * along the bottom, so a plugged-in phone cannot be stood on a table --
         * the cable is underneath it. Reverse landscape puts the edge with only
         * the volume rocker down, which sits flat. So [ORIENT_LANDSCAPE_FLIPPED]
         * is the default, and the other one is still offered because which way
         * you hold it is a preference, not a fact.
         */
        const val ORIENT_PORTRAIT = "portrait"
        const val ORIENT_LANDSCAPE = "landscape"
        const val ORIENT_LANDSCAPE_FLIPPED = "landscape_flipped"

        /**
         * [burnSubtitles] overrides the global setting for this one playback.
         *
         * Null means "use whatever Settings says", which is right for every
         * caller that has not looked at the item's own tracks. The details page
         * passes a real value, because it has: Plex stores the subtitle choice
         * **per item, on the server**, so an episode can have a track selected
         * from a TV while this phone's global switch is off. Without this the
         * details page would show "Subtitles: English" and then play it without
         * any, which is the sort of disagreement that makes a feature look
         * broken rather than off.
         */
        fun intent(
            ctx: Context,
            ratingKey: String,
            title: String,
            subtitle: String,
            startMs: Long,
            burnSubtitles: Boolean? = null,
        ): Intent = Intent(ctx, PlayerActivity::class.java)
            .putExtra(EXTRA_KEY, ratingKey)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SUBTITLE, subtitle)
            .putExtra(EXTRA_START, startMs)
            .apply { burnSubtitles?.let { putExtra(EXTRA_SUBTITLES, it) } }
    }

    private lateinit var playerView: PlayerView
    private lateinit var positionView: TextView
    private lateinit var durationView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var stateView: TextView
    private lateinit var chrome: View

    /** Hides the overlay again after [CHROME_MS] of no key presses. */
    private val chromeHider = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var ticker: Job? = null

    private lateinit var ratingKey: String

    /**
     * Where the next attempt at this stream should begin.
     *
     * Starts as whatever the caller asked to resume from and moves forward as
     * a stream is rebuilt, so a recovery picks up where the picture stopped
     * rather than at the beginning of the episode.
     */
    private var startMs = 0L

    /** Not a val: a rebuilt stream needs an identifier the server has not seen. */
    private var session = PlexPlayback.newSession()

    /** Set once the transcode is actually running, so we know to tear it down. */
    private var sessionStarted = false

    /** Rebuilds spent so far, against [MAX_RECOVERIES]. */
    private var recoveries = 0

    /** Position at the last rebuild, for deciding when to forgive it. */
    private var recoveryAnchorMs = 0L

    /**
     * True when the picture is coming off local storage rather than the server.
     *
     * Guards the two things that only make sense against a live transcode:
     * seeking has to start from [startMs] ourselves rather than being handed to
     * the transcoder as an offset, and there is no session to stop. Progress is
     * still reported when a server happens to be reachable -- watching an
     * episode on a train should still land the resume point on the TV at home.
     */
    private var playingLocally = false

    private val uri get() = store.serverUri
    private val token get() = store.serverToken

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ratingKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        startMs = intent.getLongExtra(EXTRA_START, 0L)

        // Before the window is on screen, so the first frame is already the
        // right way up rather than rotating under the user.
        applyOrientation()

        val body = layoutInflater.inflate(R.layout.activity_player, null)
        setBody(body)
        playerView = body.findViewById(R.id.player_view)
        positionView = body.findViewById(R.id.player_position)
        durationView = body.findViewById(R.id.player_duration)
        progressView = body.findViewById(R.id.player_progress)
        stateView = body.findViewById(R.id.player_state)
        chrome = body.findViewById(R.id.player_chrome)

        body.findViewById<TextView>(R.id.player_title).text =
            intent.getStringExtra(EXTRA_TITLE).orEmpty()
        body.findViewById<TextView>(R.id.player_subtitle).text =
            intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()

        // Status bar, header and softkey bar all go. See setImmersive: they are
        // 72 of the 240 rows this screen has, and the picture is the point.
        // The chrome overlay in this layout replaces them, transiently.
        setImmersive()

        // Without this the panel blanks mid-episode on the stock timeout, and
        // on a device where the lid is the real screen control that reads as a
        // crash. Cleared automatically when the activity goes away.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        startPlayback()
    }

    // ---- rotation ----------------------------------------------------------

    private fun orientation(): String = store.playerOrientation ?: ORIENT_LANDSCAPE_FLIPPED

    /**
     * Turn the screen, without losing the stream.
     *
     * `requestedOrientation` overrides the manifest, and the manifest declares
     * `orientation|screenSize` in configChanges -- so this re-lays the views out
     * in place instead of destroying and recreating the activity. That matters
     * here more than anywhere else in the app: a recreate would tear down
     * ExoPlayer, and tearing down a transcode mid-episode means preflighting a
     * new one against a session the server still thinks is live.
     */
    private fun applyOrientation() {
        requestedOrientation = when (orientation()) {
            ORIENT_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ORIENT_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
    }

    private fun setOrientation(value: String) {
        store.playerOrientation = value
        applyOrientation()
        // The new orientation is worth seeing labelled, and the user has just
        // come out of a menu -- so re-arm rather than leaving the controls up.
        showChrome()
    }

    private fun startPlayback() {
        // Clears the error a rebuild is recovering from. Leaving it up would
        // put "Playback failed" over a picture that is playing again.
        showMessage(null)
        stateView.text = getString(R.string.msg_loading)

        // A downloaded copy wins over the server, always, and without asking.
        // The file is already the right resolution -- it was transcoded on the
        // way in -- so streaming it again would spend the radio and the
        // server's transcoder to arrive at the same picture. This is also the
        // only path that works with the Wi-Fi off, which is the entire reason
        // downloads exist.
        val local = Downloads.playableFile(this, ratingKey)
        if (local != null) {
            Log.i(TAG, "playing the local copy of $ratingKey")
            playingLocally = true
            attachPlayer(local.toURI().toString())
            return
        }

        val u = uri
        val t = token
        if (u == null || t == null || ratingKey.isEmpty()) {
            showMessage(getString(R.string.msg_no_server))
            return
        }

        val url = PlexPlayback.streamUrl(
            u, t, ratingKey, session, startMs / 1000,
            quality = Quality.byId(store.quality),
            subtitles = intent.getBooleanExtra(EXTRA_SUBTITLES, store.subtitles),
            subtitleSize = store.subtitleSize,
            direct = store.directPlay,
        )

        lifecycleScope.launch {
            closeOutPreviousPlayback(u, t)
            store.lastSession = session
            store.lastRatingKey = ratingKey

            // Preflight before handing the screen over. Plex answers a bare 400
            // when its transcoder is briefly wedged and recovers within a few
            // seconds; without this the user sees a black rectangle and a bounce
            // back to the list, which is indistinguishable from a broken app.
            val code = PlexPlayback.preflight(url, t)
            if (code != 200) {
                // Mid-playback, try again rather than giving up: the common
                // reason a rebuild's preflight fails is that the radio has not
                // finished reassociating yet. This handset drops its own Wi-Fi
                // and comes back a few seconds later -- observed twice in ten
                // minutes on a -38 dBm link, with `locally_generated=1`, so the
                // phone is leaving rather than being pushed -- and each
                // preflight already spends about twenty seconds trying, so
                // three of them is a minute of patience before the user is told
                // anything.
                //
                // The first attempt is deliberately not this patient. Pressing
                // Play and waiting a silent minute to be told it did not work
                // is worse than being told in five seconds.
                if (recoveries > 0 && rebuildStream("preflight HTTP $code")) return@launch
                showMessage("Server would not start the stream.\nHTTP $code")
                stateView.text = ""
                return@launch
            }
            sessionStarted = true
            attachPlayer(url)
        }
    }

    /**
     * Throw the stream away and ask for a new one from where the picture froze.
     *
     * This is the answer to two separate reports that turn out to be the same
     * fault. One is the lid being shut for a few minutes and playback dying
     * some minutes after it is opened again -- Plex reaped the transcode, left
     * the segments it had already written, and the client sailed on through
     * them until it reached one that was never produced. The other is a stream
     * simply stopping partway through a busy evening, which is that same reap
     * arriving by a different route: a transcoder throttled to a standstill
     * behind five other clients.
     *
     * In both cases the item is fine, the server is fine, and the only thing
     * wrong is that *this* session no longer exists. Asking for another one at
     * the current position turns a dead end into a few seconds of buffering.
     *
     * [PlexPlayback.ping] is the other half and the better half: it stops most
     * of these happening at all. This is what catches the rest, including every
     * cause nobody has thought of.
     */
    private fun rebuildStream(why: String): Boolean {
        if (playingLocally) return false
        if (recoveries >= MAX_RECOVERIES) {
            Log.w(TAG, "not rebuilding again after $recoveries attempts ($why)")
            return false
        }
        val at = player?.currentPosition?.coerceAtLeast(0) ?: startMs
        recoveries++
        recoveryAnchorMs = at
        Log.i(TAG, "rebuilding the stream at ${at}ms, attempt $recoveries ($why)")

        // The position has to be in the store before the new attempt runs, or
        // closeOutPreviousPlayback sends a `stopped` timeline carrying whatever
        // the last ten-second report happened to say -- which is the resume
        // point the user would find on their TV.
        store.lastPositionMs = at
        player?.release()
        player = null
        playerView.player = null
        ticker?.cancel()

        // A new identifier, because the old session is exactly what the server
        // has stopped believing in. Reusing it asks Plex to resume something it
        // has already thrown away.
        session = PlexPlayback.newSession()
        sessionStarted = false
        startMs = at
        showChrome(sticky = true)
        stateView.text = ""
        startPlayback()
        return true
    }

    /**
     * Close out a playback the app never finished, before starting a new one.
     *
     * `onDestroy` does this on the clean path, but it does not run when the
     * process is killed -- a crash, a force-stop, or `adb install -r` over a
     * running build. What is left behind is worse than an orphaned transcode:
     * **Plex refuses a new transcode for any item it believes still has a live
     * session, and that refusal is item-scoped, not client-scoped.** Measured
     * against 1.43.2 -- with a stale session on one ratingKey, a request from a
     * completely different client identifier was refused with the same bare 400,
     * while a different item from our own client succeeded. A `state=stopped`
     * timeline cleared it immediately.
     *
     * So this sends the timeline first; the transcode stop is the cheap part.
     * The position comes from the store rather than being zeroed, because
     * `stopped` with `time=0` would wipe the user's resume point and turn a
     * crash into lost progress.
     */
    private suspend fun closeOutPreviousPlayback(u: String, t: String) {
        val staleKey = store.lastRatingKey ?: return
        Log.i(TAG, "closing out unfinished playback of $staleKey")
        PlexPlayback.timeline(
            u, t, staleKey, "stopped",
            store.lastPositionMs, store.lastDurationMs,
        )
        store.lastSession?.let { if (it != session) PlexPlayback.stop(u, t, it) }
        store.clearLastPlayback()
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
                        // Reaching the end is the only moment we can honestly
                        // say the download has served its purpose, so it is the
                        // only place this setting acts. Off by default -- see
                        // Store.downloadDeleteWatched.
                        if (store.downloadDeleteWatched) {
                            Downloads.playableFile(this@PlayerActivity, ratingKey)?.let {
                                Downloads.remove(this@PlayerActivity, ratingKey)
                            }
                        }
                        finish()
                    }
                    else -> Unit
                }
            }

            /**
             * The only place the controls are armed to disappear.
             *
             * Playback starting is what starts the countdown -- not the key
             * press that asked for it, which happens seconds earlier while the
             * server is still opening the transcode. Arming it there would fade
             * the controls out during the buffering they exist to explain, and
             * leave the user staring at a black rectangle.
             */
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                paint()
                showChrome(sticky = !isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "playback failed: ${error.errorCodeName}", error)
                // A stream that dies is nearly always a session the server has
                // forgotten, and that is recoverable without troubling anyone.
                // A local file that dies is a broken file, and no amount of
                // asking again will change it.
                if (rebuildStream(error.errorCodeName)) return
                showMessage(explain(error))
                stateView.text = ""
            }
        })

        // The token is already in the query string, so no custom DataSource
        // factory is needed here -- see PlexPlayback.params for why identity is
        // carried that way rather than in headers.
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        // A stream is already positioned by `offset` in the transcode request,
        // so seeking it here would move it twice. A local file has no such
        // parameter and has to be seeked after prepare.
        if (playingLocally && startMs > 0) exo.seekTo(startMs)
        exo.playWhenReady = true
        startTicker()
    }

    // ---- progress and reporting -------------------------------------------

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            var sinceReport = 0L
            var sincePing = 0L
            while (isActive) {
                paint()
                val p = player
                if (p != null && p.isPlaying) {
                    sinceReport += 500
                    if (sinceReport >= TIMELINE_INTERVAL_MS) {
                        sinceReport = 0
                        report("playing", p.currentPosition)
                    }
                    // A stream that has run on for a minute since the last
                    // rebuild has recovered, so the budget goes back. Without
                    // this a long film that hiccups four times in two hours
                    // would spend its last recovery on the first hour and have
                    // nothing left for the second.
                    if (recoveries > 0 &&
                        p.currentPosition - recoveryAnchorMs > RECOVERY_FORGIVEN_MS
                    ) {
                        recoveries = 0
                    }
                } else if (sessionStarted) {
                    // Paused, buffering, or waiting on the transcode: nothing is
                    // asking the server for segments, which is the state a
                    // transcode gets reaped in. This is what the lid being shut
                    // looks like from here.
                    sincePing += 500
                    if (sincePing >= PING_INTERVAL_MS) {
                        sincePing = 0
                        keepAlive()
                    }
                }
                delay(500)
            }
        }
    }

    /**
     * Say what went wrong in words, having already tried to fix it.
     *
     * By the time this is reached the stream has been rebuilt three times and
     * still failed, so the user is owed something they can act on. The raw
     * `errorCodeName` is not that: `ERROR_CODE_IO_BAD_HTTP_STATUS` was what a
     * reaped transcode session put on the screen, and it tells the person
     * holding the phone nothing about what to do next -- which on this handset
     * is very often "the Wi-Fi has dropped again".
     *
     * The code is kept on the last line anyway. It is the only thing that makes
     * a report reproducible, and it costs one line of a screen that is
     * otherwise showing a failure.
     */
    private fun explain(error: PlaybackException): String {
        val what = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Lost the connection to Plex.\nCheck Wi-Fi."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "The server stopped sending.\nTry playing it again."
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                if (playingLocally) "The saved copy is missing." else "The server lost the stream."
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                "This phone cannot decode that."
            else -> "Playback failed."
        }
        return "$what\n\n${error.errorCodeName}"
    }

    /** Fire-and-forget "we are still here" for the transcode session. */
    private fun keepAlive() {
        val u = uri ?: return
        val t = token ?: return
        lifecycleScope.launch { PlexPlayback.ping(u, t, session) }
    }

    private fun paint() {
        val p = player ?: return
        val pos = p.currentPosition.coerceAtLeast(0)
        val dur = p.duration.let { if (it == C.TIME_UNSET) 0 else it }

        positionView.text = clock(pos)
        durationView.text = if (dur > 0) clock(dur) else ""
        progressView.progress = if (dur > 0) ((pos.toFloat() / dur) * 1000).toInt() else 0
        // A glyph, not a label: the landscape transport line has room for the
        // times and the bar, and "Pause"/"Play" spelled out pushes the duration
        // off the right edge on a 320dp row.
        stateView.text = if (p.isPlaying) "❚❚" else "▶"

        // Backstop for the same rule onIsPlayingChanged applies: never leave a
        // stopped picture with no controls on it. A frozen frame and nothing
        // else looks exactly like the app has died, and that is precisely what
        // the user sees every time they reopen the lid.
        if (!p.isPlaying && chrome.visibility != View.VISIBLE) showChrome(sticky = true)
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

        // Mirrored locally on every report. If the process is killed before the
        // next one, this is the position closeOutPreviousPlayback will send --
        // without it the recovery report would have to guess, and guessing zero
        // wipes the resume point.
        store.lastPositionMs = positionMs
        store.lastDurationMs = dur

        lifecycleScope.launch {
            PlexPlayback.timeline(u, t, ratingKey, state, positionMs, dur)
        }
    }

    // ---- the transient overlay ---------------------------------------------

    /**
     * Show the controls, and arm their disappearance.
     *
     * Called on every key press, so any interaction brings the overlay back --
     * which is the only way it can be brought back, there being no touchscreen
     * to tap. While paused it is left up permanently: a paused player showing
     * nothing but a frozen frame is indistinguishable from a crashed one, and
     * that is exactly the state the phone is in every time the lid is opened.
     *
     * `removeCallbacksAndMessages` runs on both paths on purpose. A sticky call
     * has to *cancel* a pending hide, or the controls the pause just pinned up
     * vanish a second later because a timer armed before the pause is still in
     * flight.
     */
    private fun showChrome(sticky: Boolean = false) {
        chrome.visibility = View.VISIBLE
        chromeHider.removeCallbacksAndMessages(null)
        if (!sticky) {
            chromeHider.postDelayed({ chrome.visibility = View.GONE }, CHROME_MS)
        }
    }

    /**
     * Any key at all wakes the controls, including the two softkeys, which are
     * handled by the shell and never reach [onAction].
     *
     * Without this the only way to see where you are in an episode would be to
     * press something that changes playback.
     */
    override fun onKeyPressed(action: Action?) {
        // Anything that is not actively playing -- paused, buffering, or still
        // waiting for the transcode -- keeps the controls up rather than
        // arming a fade the user did not ask for.
        showChrome(sticky = player?.isPlaying != true)
    }

    // ---- keys --------------------------------------------------------------

    /**
     * The one screen where the search key does nothing.
     *
     * Leaving the player finishes it, which tears down ExoPlayer and posts the
     * `stopped` timeline -- so binding search here would mean a key next to the
     * D-pad that silently ends the episode. The transcode would go too, and the
     * user would come back to a paused resume point wondering what they hit.
     */
    override fun openSearch() = Unit

    override fun optionsHeading(): String = intent.getStringExtra(EXTRA_TITLE).orEmpty()

    override fun optionsFor(): List<Option> = buildList {
        add(
            Option("Restart from beginning") {
                player?.seekTo(0)
                player?.play()
            }
        )
        // A tick rather than a separate "current orientation" line: the panel is
        // the only place these three appear, so the marker has to carry the
        // state as well as the choice.
        fun rotate(value: String, label: String) {
            add(Option(if (orientation() == value) "$label  ✓" else label) { setOrientation(value) })
        }
        // The labels do not track the constant names, deliberately. The
        // constants are named for what Android calls them; the labels are named
        // for what the user sees, and what they see is that one of these is the
        // normal way to hold it and the other is the same thing upside down.
        rotate(ORIENT_LANDSCAPE_FLIPPED, "Landscape")
        rotate(ORIENT_LANDSCAPE, "Landscape, other way")
        rotate(ORIENT_PORTRAIT, "Portrait")
        add(Option("Stop") { finish() })
    }

    override fun onAction(action: Action, keyCode: Int): Boolean {
        // The overlay is woken in onKeyPressed, which runs for every key the
        // app keeps -- including the softkeys, which never arrive here.
        val p = player ?: return false
        // Which way "up" points depends on which way the handset is being held.
        val flipped = orientation() == ORIENT_LANDSCAPE_FLIPPED
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
            // Both axes seek, and that is because of the rotation.
            //
            // The user physically turns the handset for this screen, and the
            // D-pad turns with it. In landscape the key printed "up" no longer
            // points up -- it points along the picture, so a strict LEFT/RIGHT
            // binding would have people pressing a key aimed at the start of the
            // film and getting nothing. Up and down have no other job here, so
            // they seek too.
            //
            // Which way they seek has to follow the rotation, though. The two
            // landscapes point the D-pad at opposite ends of the picture, so a
            // fixed "up means back" is right in one of them and backwards in the
            // other. LEFT and RIGHT keep their printed meaning in every case.
            Action.LEFT -> { seek(-1, p); true }
            Action.RIGHT -> { seek(+1, p); true }
            Action.UP -> { seek(if (flipped) +1 else -1, p); true }
            Action.DOWN -> { seek(if (flipped) -1 else +1, p); true }
            else -> false
        }
    }

    /**
     * The D-pad, as the picture sees it.
     *
     * Measured on the handset: in plain landscape the panel is at ROTATION_90
     * and the key printed "up" points at the left of the picture; reverse
     * landscape is ROTATION_270 and it points at the right. So the key that
     * moves *down* a list drawn over the video is LEFT in one and RIGHT in the
     * other, and neither of them is the one with the arrow on it.
     *
     * Only the Options panel uses this. Seeking does its own thing, because
     * there the question is which key points at the start of the film rather
     * than which way a list runs.
     */
    override fun screenDirection(action: Action?): Action? = when (orientation()) {
        ORIENT_PORTRAIT -> action
        ORIENT_LANDSCAPE -> when (action) {
            Action.RIGHT -> Action.UP
            Action.LEFT -> Action.DOWN
            Action.UP -> Action.LEFT
            Action.DOWN -> Action.RIGHT
            else -> action
        }
        else -> when (action) {
            Action.LEFT -> Action.UP
            Action.RIGHT -> Action.DOWN
            Action.UP -> Action.RIGHT
            Action.DOWN -> Action.LEFT
            else -> action
        }
    }

    /** [dir] is -1 for back, +1 for forward. */
    private fun seek(dir: Int, p: ExoPlayer) {
        p.seekTo((p.currentPosition + dir * SEEK_MS).coerceAtLeast(0))
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
        chromeHider.removeCallbacksAndMessages(null)
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
            // Cleared only on the clean path. If we never get here -- killed
            // process -- the record survives in prefs and the next startPlayback
            // closes it out, which is the whole point of storing it.
            store.clearLastPlayback()
        }
        super.onDestroy()
    }
}
