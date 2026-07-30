package io.github.jackharvest.flipflex.plex

import org.json.JSONObject

/**
 * `optString` is not safe on Plex's JSON, and this is the fix.
 *
 * Android's `JSONObject.optString(name, fallback)` returns the fallback only
 * when the key is *absent*. When the key is present and explicitly `null` --
 * which is how Plex reports "not linked yet", "no access token", "this episode
 * has no grandparent" -- it returns the **four-character string `"null"`**,
 * because `JSONObject.NULL` is a sentinel object and `String.valueOf()` renders
 * it rather than yielding a Java null.
 *
 * That cost us a real bug on the very first run: `/pins/<id>` answers
 * `{"authToken": null}` until the user types the code, so `optString` handed
 * back `"null"`, the emptiness check passed, and the app stored `"null"` as the
 * account token and walked straight past the sign-in screen. Every later call
 * would then have failed with a 401 that looked like an expired login.
 *
 * So: never call `optString` on Plex JSON. Call [str] or [strOrNull].
 */
fun JSONObject.str(name: String, fallback: String = ""): String =
    if (isNull(name)) fallback else optString(name, fallback)

/** The same, collapsing both "absent" and "explicitly null" to a Kotlin null. */
fun JSONObject.strOrNull(name: String): String? =
    if (isNull(name)) null else optString(name, "").ifEmpty { null }
