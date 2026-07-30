package io.github.jackharvest.flipflex.plex

import android.util.Log
import java.util.UUID

/**
 * Turning a ratingKey into something ExoPlayer can open, and telling the server
 * what happened afterwards.
 */
object PlexPlayback {

    private const val TAG = "FlipFlex/play"

    /**
     * What we ask the transcoder for.
     *
     * The panel is 240x320 at density 160, so a 16:9 video occupies 240x135 of
     * it. 320x240 still oversamples that, and it is a quarter of the pixels of
     * the 640x480 PocketFlex asks for on a physically larger screen. The point
     * is not to save the SoC -- `OMX.MTK.VIDEO.DECODER.AVC` handles 1600x960 in
     * hardware, measured off /vendor/etc/media_codecs_mediatek_video.xml -- it
     * is to save the server's transcoder and the radio.
     */
    const val RESOLUTION = "320x240"
    const val MAX_BITRATE = 800

    /**
     * A session identifier Plex uses to tie the transcode, the timeline and the
     * teardown together. One per playback, not one per app launch: reusing an
     * identifier across two plays makes the server tear the first transcode
     * down when the second starts, which shows up as playback dying at the
     * moment you start something else.
     */
    fun newSession(): String = UUID.randomUUID().toString().replace("-", "").take(24)

    /**
     * Query-string parameters shared by every transcode request.
     *
     * `directPlay=0&directStream=0` forces a real transcode. That is the right
     * default for a proof of concept even though this SoC could direct-play a
     * lot: it makes playback depend on one code path that behaves the same for
     * every file in the library, so a failure is a failure of *our* plumbing
     * rather than of whatever container that particular file happens to use.
     * Direct play is the obvious optimisation once this path is proven.
     */
    private fun params(session: String, offsetSec: Long, token: String): String = buildString {
        append("&mediaIndex=0&partIndex=0")
        append("&offset=$offsetSec&fastSeek=1")
        append("&directPlay=0&directStream=0")
        append("&videoQuality=60&videoResolution=$RESOLUTION&maxVideoBitrate=$MAX_BITRATE")
        // ExoPlayer can render a soft subtitle track, unlike the ffplay
        // PocketFlex is stuck with, so there is no need to burn them into the
        // frames here. Left off entirely until the picture is proven.
        append("&subtitles=none")
        append("&audioBoost=100")
        append("&session=$session&X-Plex-Session-Identifier=$session")
        // Identity repeated in the query string, not only in headers: ExoPlayer
        // applies its request headers to the playlist and the segments, but a
        // redirect to a different host can drop them, and Plex answers a bare
        // 400 for a segment request it cannot attribute to a session.
        append("&X-Plex-Client-Identifier=${PlexClient.clientId}")
        append("&X-Plex-Product=${PlexClient.PRODUCT}")
        append("&X-Plex-Platform=${PlexClient.PLATFORM}")
        append("&X-Plex-Token=${PlexClient.enc(token)}")
    }

    /**
     * The HLS playlist URL.
     *
     * HLS rather than a single progressive stream, for the same reason
     * PocketFlex chose it: a stalled segment is recoverable, where a broken
     * pipe on a continuous stream just ends playback. On a phone that will be
     * carried around a house on Wi-Fi, that difference is the whole experience.
     */
    fun streamUrl(uri: String, token: String, ratingKey: String, session: String, offsetSec: Long = 0): String =
        "$uri/video/:/transcode/universal/start.m3u8" +
            "?path=${PlexClient.enc("/library/metadata/$ratingKey")}" +
            "&protocol=hls" +
            params(session, offsetSec, token)

    /**
     * Confirm the server will serve this before we hand the screen to the player.
     *
     * Plex answers a bare 400 -- no body, no reason -- when its transcoder is
     * briefly wedged, and recovers within a few seconds. Without this the user
     * gets a black screen and a silent bounce back to the list, which is
     * indistinguishable from the app being broken.
     */
    suspend fun preflight(url: String, token: String): Int {
        var code = -1
        BACKOFF_MS.forEachIndexed { attempt, wait ->
            code = PlexClient.status(url, token)
            if (code == 200) return 200
            Log.w(TAG, "preflight attempt ${attempt + 1} got HTTP $code, waiting ${wait}ms")
            if (wait > 0) kotlinx.coroutines.delay(wait)
        }
        return code
    }

    /**
     * Waits between preflight attempts. Total patience is about 20 seconds.
     *
     * The first version tried three times over six seconds and that was not
     * enough: with five other clients on the server and three transcodes already
     * running, a title that had played a minute earlier returned 400 on every
     * attempt, and the identical URL succeeded by hand shortly after. A busy
     * household is the normal case for a home Plex server, so the retry has to
     * outlast a transcoder queue rather than just a momentary hiccup.
     */
    private val BACKOFF_MS = longArrayOf(1_500, 3_000, 5_000, 8_000, 0)

    /**
     * Report progress. This is what makes resume work -- here and on every
     * other client on the account.
     *
     * [state] is one of `playing`, `paused`, `stopped`. The `stopped` report on
     * lid-close is the one that matters most for this handset: it is what puts
     * the position on the server so picking the show up on a TV lands in the
     * right place.
     */
    suspend fun timeline(
        uri: String,
        token: String,
        ratingKey: String,
        state: String,
        timeMs: Long,
        durationMs: Long,
    ) {
        PlexClient.text(
            "$uri/:/timeline?ratingKey=$ratingKey" +
                "&key=${PlexClient.enc("/library/metadata/$ratingKey")}" +
                "&state=$state&time=$timeMs&duration=$durationMs",
            token,
        )
    }

    /**
     * Tear the transcode session down.
     *
     * Not optional politeness: skipping it leaves an ffmpeg process running on
     * the server until Plex times it out, and a handful of those on a NAS is
     * enough to make the next play attempt fail preflight.
     */
    suspend fun stop(uri: String, token: String, session: String) {
        PlexClient.text("$uri/video/:/transcode/universal/stop?session=$session", token)
    }
}
