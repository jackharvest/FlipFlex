package io.github.jackharvest.flipflex.plex

import android.util.Log
import org.json.JSONArray

/**
 * Plex Home profile switching.
 *
 * A shared household server is the normal case for this app -- the one it was
 * developed against had four other clients streaming at the time -- and watch
 * state, Continue Watching and resume points are all per-profile. Without this,
 * the flip phone reports every episode against whoever happened to link it,
 * quietly corrupting somebody else's Continue Watching.
 */
object PlexProfiles {

    private const val TAG = "FlipFlex/profiles"

    data class User(
        val uuid: String,
        val id: String,
        val title: String,
        val protected: Boolean,
        val admin: Boolean,
    )

    /** Everyone on this Plex Home. Empty if the account has no Home set up. */
    suspend fun users(accountToken: String): List<User> {
        val o = PlexClient.json("${PlexClient.PLEX_TV}/home/users", accountToken) ?: return emptyList()
        val arr: JSONArray = o.optJSONArray("users") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val u = arr.optJSONObject(i) ?: return@mapNotNull null
            val uuid = u.str("uuid")
            if (uuid.isEmpty()) null else User(
                uuid = uuid,
                id = u.optLong("id", 0L).toString(),
                title = u.str("title", "Profile"),
                protected = u.optBoolean("protected", false),
                admin = u.optBoolean("admin", false),
            )
        }
    }

    /**
     * Switch to a profile and return that profile's token.
     *
     * The v2 endpoint is the current one. v1 is kept as a fallback because
     * older accounts still answer there and the failure is otherwise silent --
     * a null return that looks identical to a wrong PIN.
     */
    suspend fun switchTo(accountToken: String, uuid: String, pin: String? = null): String? {
        val q = if (pin.isNullOrEmpty()) "" else "?pin=${PlexClient.enc(pin)}"

        PlexClient.json("${PlexClient.PLEX_TV}/home/users/$uuid/switch$q", accountToken, method = "POST")
            ?.strOrNull("authToken")
            ?.let { return it }

        Log.w(TAG, "v2 switch returned nothing for $uuid; trying v1")
        val v1 = PlexClient.text(
            "https://plex.tv/api/home/users/$uuid/switch$q",
            accountToken,
            method = "POST",
        ) ?: return null

        // v1 answers XML. One attribute is wanted and pulling it with a regex
        // is not worth an XML parser on a device with a 128 MB heap.
        return Regex("""(?:authenticationToken|authToken)="([^"]+)"""")
            .find(v1)
            ?.groupValues
            ?.get(1)
    }
}
