package io.github.jackharvest.flipflex.plex

/**
 * Sign-in, via the four-character code at plex.tv/link.
 *
 * This is the only sign-in flow worth having on this handset. Typing an email
 * address and a Plex password on a T9 keypad -- with no touch keyboard, no
 * password manager and a 240x320 screen -- would be miserable and would put the
 * user's actual credentials through a numeric keypad. The link flow means the
 * phone only ever displays four characters and receives a token.
 */
object PlexAuth {

    data class Pin(val id: Long, val code: String)

    /**
     * Ask plex.tv for a new link code.
     *
     * `strong=false` is deliberate and is the whole point: `strong=true`
     * returns a 25-character code that cannot be typed into plex.tv/link at
     * all. The short code is what makes this usable.
     */
    suspend fun newPin(): Pin? {
        val o = PlexClient.json("${PlexClient.PLEX_TV}/pins?strong=false", method = "POST")
            ?: return null
        val id = o.optLong("id", 0L)
        val code = o.str("code")
        return if (id != 0L && code.isNotEmpty()) Pin(id, code) else null
    }

    /**
     * Has the user linked yet? Returns the auth token, or null to keep waiting.
     *
     * Null is the *normal* answer here, not an error -- it is what plex.tv says
     * every time we poll before the user has finished typing the code.
     *
     * [strOrNull] rather than `optString`, and that distinction is load-bearing:
     * an unlinked pin answers `{"authToken": null}`, and `optString` renders
     * that as the string `"null"`. See Json.kt -- this exact call is where that
     * bug was found.
     */
    suspend fun checkPin(id: Long): String? {
        val o = PlexClient.json("${PlexClient.PLEX_TV}/pins/$id") ?: return null
        return o.strOrNull("authToken")
    }

    /** The account name for a token, or null if the token is no longer good. */
    suspend fun validate(token: String): String? {
        val o = PlexClient.json("${PlexClient.PLEX_TV}/user", token) ?: return null
        return o.strOrNull("username") ?: o.strOrNull("title")
    }
}
