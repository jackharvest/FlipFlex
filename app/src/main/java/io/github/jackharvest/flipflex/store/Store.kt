package io.github.jackharvest.flipflex.store

import android.content.Context
import android.content.SharedPreferences
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
    }
}
