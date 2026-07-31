package com.github.jackharvest.flipflex.store

import android.content.Context
import android.content.SharedPreferences
import com.github.jackharvest.flipflex.plex.Quality
import java.util.UUID

/**
 * Everything FlipFlex remembers between launches.
 *
 * SharedPreferences rather than a database: the whole persisted state is a
 * token, a server address and a handful of settings. Room would add a schema,
 * a code generator and a migration story to store six strings.
 */
class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("flipflex", Context.MODE_PRIVATE)

    /**
     * A stable per-install identifier.
     *
     * Plex uses this to tell devices apart in the account's device list, and it
     * must not change between launches -- a new id on every start would litter
     * the user's account with one "FlipFlex" entry per app launch, and Plex
     * eventually rate-limits an account that does that.
     */
    val clientId: String
        get() = prefs.getString(K_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(K_CLIENT_ID, it).apply()
        }

    /**
     * The token in use right now. May belong to a Plex Home profile rather than
     * to the account that linked the device.
     */
    var token: String?
        get() = prefs.getString(K_TOKEN, null)?.ifEmpty { null }
        set(v) = prefs.edit().putString(K_TOKEN, v ?: "").apply()

    /**
     * The token from the original plex.tv/link, kept separately.
     *
     * Switching profiles replaces [token], and the profile token cannot be
     * relied on to switch *again* -- a managed user's token is not guaranteed to
     * be allowed to enumerate or assume other profiles. Keeping the linking
     * token means profile switching still works after the first switch, instead
     * of stranding the device on whichever profile it last chose.
     */
    var homeToken: String?
        get() = prefs.getString(K_HOME_TOKEN, null)?.ifEmpty { null } ?: token
        set(v) = prefs.edit().putString(K_HOME_TOKEN, v ?: "").apply()

    /** Display name of the active profile, for the Settings screen. */
    var profileName: String?
        get() = prefs.getString(K_PROFILE_NAME, null)?.ifEmpty { null }
        set(v) = prefs.edit().putString(K_PROFILE_NAME, v ?: "").apply()

    /**
     * Libraries the user has hidden from the home screen.
     *
     * Stored as the set of hidden keys rather than the set of shown ones, so a
     * library added to the server later appears by default. The opposite
     * default -- an allow-list -- means new content silently never shows up,
     * which is a far worse failure than having to hide something once.
     */
    var hiddenSections: Set<String>
        get() = prefs.getStringSet(K_HIDDEN_SECTIONS, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_HIDDEN_SECTIONS, v).apply()

    fun toggleSectionHidden(sectionKey: String) {
        hiddenSections = hiddenSections.toMutableSet().apply {
            if (!add(sectionKey)) remove(sectionKey)
        }
    }

    /**
     * The order the user dragged the libraries into on the home screen.
     *
     * A **list**, not a map of positions, because the two disagree the moment
     * the server gains or loses a library and only one of them degrades well:
     * anything not named here keeps its server order and lands after everything
     * that is. So a new library appears at the bottom rather than displacing a
     * carefully arranged list, and a deleted one leaves no hole.
     *
     * Stored as one string with a separator that cannot occur in a section key
     * -- Plex's keys are decimal integers. `getStringSet` would have been the
     * obvious type and is exactly wrong: a Set does not keep order, which is
     * the only thing this value is for.
     */
    var sectionOrder: List<String>
        get() = prefs.getString(K_SECTION_ORDER, null)
            ?.split(ORDER_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        set(v) = prefs.edit().putString(K_SECTION_ORDER, v.joinToString(ORDER_SEPARATOR)).apply()

    /**
     * Which way the player screen is turned. See `PlayerActivity.ORIENT_*`.
     *
     * Null means "never chosen", and the player picks its own default -- the
     * value is deliberately not defaulted here, because which of the two
     * landscapes is the good one is a fact about the handset's edges rather
     * than about storage.
     */
    var playerOrientation: String?
        get() = prefs.getString(K_PLAYER_ORIENTATION, null)?.ifEmpty { null }
        set(v) = prefs.edit().putString(K_PLAYER_ORIENTATION, v ?: "").apply()

    // ---- playback settings -------------------------------------------------

    /**
     * Streaming quality. An id from [com.github.jackharvest.flipflex.plex.Quality].
     */
    var quality: String
        get() = prefs.getString(K_QUALITY, null)?.ifEmpty { null } ?: Quality.DEFAULT_STREAM
        set(v) = prefs.edit().putString(K_QUALITY, v).apply()

    /**
     * Subtitles on or off, as a default for anything that has them.
     *
     * A per-item override lives on the server, not here -- picking a track sends
     * `subtitleStreamID` to `/library/parts/<id>`, which is how Plex itself
     * remembers it and why the choice follows you to a TV. This flag is only
     * the answer to "and what about everything else".
     */
    var subtitles: Boolean
        get() = prefs.getBoolean(K_SUBTITLES, false)
        set(v) = prefs.edit().putBoolean(K_SUBTITLES, v).apply()

    /**
     * Percentage passed to the transcoder as `subtitleSize`.
     *
     * Worth having as a setting rather than a constant because the panel is
     * 2.4": Plex's 100 is sized for a television, and on this screen the
     * difference between 100 and 150 is the difference between subtitles you
     * can read across a kitchen and subtitles you cannot.
     */
    var subtitleSize: Int
        get() = prefs.getInt(K_SUBTITLE_SIZE, 125)
        set(v) = prefs.edit().putInt(K_SUBTITLE_SIZE, v).apply()

    /**
     * Ask the server to send the file as it is, instead of transcoding it.
     *
     * Off, and an experiment rather than a feature. It exists because the
     * obvious suspect for a stream that dies partway through a busy evening is
     * the server's transcoder, and the only way to test that from here is to
     * take the transcoder out of the path and see whether the failures stop.
     *
     * It is not a general improvement and should not be made one without
     * evidence. The panel is 240 wide, so direct play pulls a 1080p file across
     * the radio to draw it into 320x180 -- more bandwidth and more battery for
     * the same picture. Worse, the MT6739 decodes HEVC only up to 1600x960, so
     * a 1080p HEVC source is above what the chip will accept and simply will
     * not play. Expect this to work on some of the library and fail on the rest;
     * that is what makes it a diagnostic and not a setting.
     */
    var directPlay: Boolean
        get() = prefs.getBoolean(K_DIRECT_PLAY, false)
        set(v) = prefs.edit().putBoolean(K_DIRECT_PLAY, v).apply()

    /**
     * Whether choosing something opens its details page or starts it playing.
     *
     * On by default. The details page is where the description, the source
     * resolution and the subtitle and audio tracks live, and none of that is
     * reachable from anywhere else -- but someone who only ever resumes the
     * same show will want the old one-press behaviour back, so it is a switch.
     */
    var showDetails: Boolean
        get() = prefs.getBoolean(K_SHOW_DETAILS, true)
        set(v) = prefs.edit().putBoolean(K_SHOW_DETAILS, v).apply()

    // ---- download settings -------------------------------------------------

    /** Quality downloads are fetched at. Separate from [quality] deliberately. */
    var downloadQuality: String
        get() = prefs.getString(K_DL_QUALITY, null)?.ifEmpty { null } ?: Quality.DEFAULT_DOWNLOAD
        set(v) = prefs.edit().putString(K_DL_QUALITY, v).apply()

    /**
     * Refuse to start a download on mobile data.
     *
     * On by default, and the default matters more here than on a tablet: this
     * is a phone with a real SIM in it, and a film at 320 kbps is still a few
     * hundred megabytes of somebody's plan.
     */
    var downloadWifiOnly: Boolean
        get() = prefs.getBoolean(K_DL_WIFI_ONLY, true)
        set(v) = prefs.edit().putBoolean(K_DL_WIFI_ONLY, v).apply()

    /**
     * Delete a download once it has been watched to the end.
     *
     * Off by default. Deleting something the moment you finish it is right up
     * until the moment you wanted it again on the way home, and storage is not
     * scarce here -- /data has 11 GB free.
     */
    var downloadDeleteWatched: Boolean
        get() = prefs.getBoolean(K_DL_DELETE_WATCHED, false)
        set(v) = prefs.edit().putBoolean(K_DL_DELETE_WATCHED, v).apply()

    /** Base URI of the chosen server, e.g. `https://10-0-0-4.<hash>.plex.direct:32400`. */
    var serverUri: String?
        get() = prefs.getString(K_SERVER_URI, null)?.ifEmpty { null }
        set(v) = prefs.edit().putString(K_SERVER_URI, v ?: "").apply()

    /**
     * The per-server access token.
     *
     * Not the same as [token], and conflating them is a real failure mode: a
     * server shared with you by someone else answers only to the access token
     * that came with it in /api/v2/resources, and rejects the account token.
     */
    var serverToken: String?
        get() = prefs.getString(K_SERVER_TOKEN, null)?.ifEmpty { null }
        set(v) = prefs.edit().putString(K_SERVER_TOKEN, v ?: "").apply()

    var serverName: String?
        get() = prefs.getString(K_SERVER_NAME, null)?.ifEmpty { null }
        set(v) = prefs.edit().putString(K_SERVER_NAME, v ?: "").apply()

    var serverClientId: String?
        get() = prefs.getString(K_SERVER_CLIENT_ID, null)?.ifEmpty { null }
        set(v) = prefs.edit().putString(K_SERVER_CLIENT_ID, v ?: "").apply()

    /**
     * The transcode session id of the last thing we played.
     *
     * Persisted because `PlayerActivity.onDestroy` is the only thing that tears
     * a session down, and it does not run when the app is killed -- a crash, a
     * force-stop, or `adb install -r` over a running build. The session then
     * stays open on the server and the *next* play attempt fails preflight with
     * a bare HTTP 400, which reads like a broken app rather than a leftover.
     *
     * Observed exactly that way: reinstalling mid-playback left a transcode at
     * 4.9% and the following play returned 400 until it was cleared by hand.
     */
    var lastSession: String?
        get() = prefs.getString(K_LAST_SESSION, null)?.ifEmpty { null }
        set(v) = prefs.edit().putString(K_LAST_SESSION, v ?: "").apply()

    /**
     * Enough to close out a playback the app never got to finish.
     *
     * Stopping the transcode is not sufficient on its own. Plex refuses a new
     * transcode for an item it believes still has a live session, and that
     * refusal is **item-scoped, not client-scoped** -- measured: with a stale
     * session on ratingKey 53033, a request from a completely different client
     * identifier was also refused with 400, while a different item from the same
     * client succeeded. The only thing that clears it is a `state=stopped`
     * timeline for that item.
     *
     * The position is stored with it because the recovery report must send the
     * *real* position. Sending `stopped` with `time=0` would clear the resume
     * point, turning a crash into lost progress.
     */
    var lastRatingKey: String?
        get() = prefs.getString(K_LAST_RATING_KEY, null)?.ifEmpty { null }
        set(v) = prefs.edit().putString(K_LAST_RATING_KEY, v ?: "").apply()

    var lastPositionMs: Long
        get() = prefs.getLong(K_LAST_POSITION, 0L)
        set(v) = prefs.edit().putLong(K_LAST_POSITION, v).apply()

    var lastDurationMs: Long
        get() = prefs.getLong(K_LAST_DURATION, 0L)
        set(v) = prefs.edit().putLong(K_LAST_DURATION, v).apply()

    /** Forget the in-flight playback. Called once it has been closed out. */
    fun clearLastPlayback() {
        prefs.edit()
            .remove(K_LAST_SESSION)
            .remove(K_LAST_RATING_KEY)
            .remove(K_LAST_POSITION)
            .remove(K_LAST_DURATION)
            .apply()
    }

    val isLinked: Boolean get() = token != null
    val hasServer: Boolean get() = serverUri != null && serverToken != null

    fun signOut() {
        prefs.edit()
            .remove(K_TOKEN)
            .remove(K_HOME_TOKEN)
            .remove(K_PROFILE_NAME)
            .remove(K_SERVER_URI)
            .remove(K_SERVER_TOKEN)
            .remove(K_SERVER_NAME)
            .remove(K_SERVER_CLIENT_ID)
            .apply()
    }

    private companion object {
        const val ORDER_SEPARATOR = ","
        const val K_CLIENT_ID = "client_id"
        const val K_TOKEN = "token"
        const val K_SERVER_URI = "server_uri"
        const val K_SERVER_TOKEN = "server_token"
        const val K_SERVER_NAME = "server_name"
        const val K_SERVER_CLIENT_ID = "server_client_id"
        const val K_LAST_SESSION = "last_session"
        const val K_LAST_RATING_KEY = "last_rating_key"
        const val K_LAST_POSITION = "last_position_ms"
        const val K_LAST_DURATION = "last_duration_ms"
        const val K_HOME_TOKEN = "home_token"
        const val K_PROFILE_NAME = "profile_name"
        const val K_HIDDEN_SECTIONS = "hidden_sections"
        const val K_PLAYER_ORIENTATION = "player_orientation"
        const val K_SECTION_ORDER = "section_order"
        const val K_QUALITY = "quality"
        const val K_SUBTITLES = "subtitles"
        const val K_SUBTITLE_SIZE = "subtitle_size"
        const val K_SHOW_DETAILS = "show_details"
        const val K_DIRECT_PLAY = "direct_play"
        const val K_DL_QUALITY = "dl_quality"
        const val K_DL_WIFI_ONLY = "dl_wifi_only"
        const val K_DL_DELETE_WATCHED = "dl_delete_watched"
    }
}
