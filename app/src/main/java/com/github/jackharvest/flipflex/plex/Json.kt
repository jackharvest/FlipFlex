package com.github.jackharvest.flipflex.plex

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
 *
 * Both of those also [unescape] what they return -- see that function for why a
 * JSON API hands back HTML entities at all.
 */
fun JSONObject.str(name: String, fallback: String = ""): String =
    if (isNull(name)) fallback else unescape(optString(name, fallback))

/** The same, collapsing both "absent" and "explicitly null" to a Kotlin null. */
fun JSONObject.strOrNull(name: String): String? =
    if (isNull(name)) null else unescape(optString(name, "")).ifEmpty { null }

/**
 * Turn the HTML entities Plex puts in its JSON back into the characters it
 * meant.
 *
 * Plex escapes every user-visible string as if it were going into an HTML page,
 * and does it in the JSON as well as the XML -- `plex.tv/api/v2/user` reports an
 * account called *Alice & Bob* as `"title": "Alice &amp; Bob"`. JSON has its
 * own escaping and needs none of this, but the server does it anyway, so a
 * client that takes the string at face value paints the entity on screen. That
 * is what the splash used to do, and it would equally have hit every film with
 * an ampersand or an apostrophe in its name.
 *
 * The scan is single-pass and left to right on purpose. Doing it as a series of
 * replacements gets `&amp;lt;` wrong -- expanding `&amp;` first leaves `&lt;`,
 * which the next replacement then turns into `<`, so a title that was *meant* to
 * read `&lt;` comes out as a tag. One pass consumes each entity whole and never
 * re-reads what it has already written.
 *
 * Only the five XML entities and numeric character references are handled.
 * Anything else -- `&nbsp;`, a bare `&` in *Dungeons & Dragons* that the server
 * failed to escape -- is copied through untouched, which is both what the user
 * wants to see and what makes this safe to run over every string in the API,
 * including the ones that are really URLs.
 */
fun unescape(s: String): String {
    if (s.indexOf('&') < 0) return s          // the overwhelmingly common case
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '&') { out.append(c); i++; continue }

        val end = s.indexOf(';', i + 1)
        // An unterminated '&', or one so far from its ';' that it cannot be an
        // entity, is just an ampersand. 10 covers "&thinsp;" and every numeric
        // reference we could meet.
        if (end < 0 || end - i > 10) { out.append(c); i++; continue }

        val body = s.substring(i + 1, end)
        val decoded = when {
            body == "amp" -> "&"
            body == "lt" -> "<"
            body == "gt" -> ">"
            body == "quot" -> "\""
            body == "apos" -> "'"
            body.startsWith("#x") || body.startsWith("#X") ->
                body.drop(2).toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let {
                    String(Character.toChars(it))
                }
            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let {
                    String(Character.toChars(it))
                }
            else -> null
        }

        if (decoded == null) { out.append(c); i++ } else { out.append(decoded); i = end + 1 }
    }
    return out.toString()
}
