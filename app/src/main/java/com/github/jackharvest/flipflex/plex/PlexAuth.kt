package com.github.jackharvest.flipflex.plex

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

    /**
     * Three answers, and the third one is the whole point of this type.
     *
     * This used to return `String?`, and the null meant both "plex.tv rejected
     * the token" and "plex.tv could not be reached". `SplashActivity` responded
     * to null by calling `signOut()` -- so **opening the app with no network
     * wiped the stored token and demanded a re-link at plex.tv/link**, on a
     * device whose entire offline feature is a folder full of downloads you can
     * watch on a train. It was reproduced exactly that way: Wi-Fi off, mobile
     * data off, cold start, and the sign-in screen with the token gone from
     * prefs.
     *
     * Recovering from that needs a second device with a browser, so the failure
     * is not just annoying, it is unrecoverable from where the user is standing.
     * A token must only ever be discarded because a server said to.
     */
    sealed interface Validation {
        data class Ok(val name: String) : Validation

        /** plex.tv answered, and will not accept this token. Sign out. */
        data object Rejected : Validation

        /**
         * Nothing answered. The token is untouched and may be perfectly good;
         * carry on offline.
         */
        data object Unreachable : Validation
    }

    suspend fun validate(token: String): Validation {
        val r = PlexClient.reply("${PlexClient.PLEX_TV}/user", token)
        if (!r.reached) return Validation.Unreachable
        // Any status at all means plex.tv is there and formed an opinion. A 5xx
        // is its problem rather than the token's, so it is not grounds for
        // throwing the login away either -- only an outright refusal is.
        if (r.code == 401 || r.code == 403) return Validation.Rejected
        val body = r.body ?: return Validation.Unreachable
        val o = try {
            org.json.JSONObject(body)
        } catch (e: Exception) {
            return Validation.Unreachable
        }
        val name = o.strOrNull("username") ?: o.strOrNull("title")
        return if (name == null) Validation.Unreachable else Validation.Ok(name)
    }
}
