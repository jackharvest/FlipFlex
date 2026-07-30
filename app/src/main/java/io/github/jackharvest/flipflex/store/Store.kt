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

    var token: String?
        get() = prefs.getString(K_TOKEN, null)?.ifEmpty { null }
        set(v) = prefs.edit().putString(K_TOKEN, v ?: "").apply()

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

    val isLinked: Boolean get() = token != null
    val hasServer: Boolean get() = serverUri != null && serverToken != null

    fun signOut() {
        prefs.edit()
            .remove(K_TOKEN)
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
    }
}
