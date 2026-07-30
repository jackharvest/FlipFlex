package io.github.jackharvest.flipflex.plex

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Every HTTP call FlipFlex makes to Plex, and the identity it makes them under.
 *
 * Deliberately built on HttpURLConnection rather than a bundled HTTP library:
 * Android's implementation is OkHttp underneath, and this handset has a 128 MB
 * `dalvik.vm.heapgrowthlimit` with 916 MB of RAM total. A dependency we do not
 * need is one we should not carry.
 */
object PlexClient {

    private const val TAG = "FlipFlex/net"

    const val PLEX_TV = "https://plex.tv/api/v2"

    const val PRODUCT = "FlipFlex"
    const val VERSION = "0.2.0"
    const val DEVICE = "TCL 4058G"

    /**
     * The platform we claim to be.
     *
     * This is load-bearing and was expensive to learn on PocketFlex: Plex only
     * serves the `/video/:/transcode/universal/` endpoints for a platform it
     * has a built-in client profile for. Against Plex Media Server 1.43.2,
     * `X-Plex-Platform: Linux` made every transcode request fail with a bare
     * 400 and no body, while `Chrome` worked.
     *
     * (Written without a wildcard on that path on purpose: Kotlin block
     * comments nest, so a literal slash-star inside a KDoc opens a comment that
     * never closes and the whole file stops compiling.)
     *
     * "Android" is both honest and a profile the server certainly ships, so it
     * is what we send. If a transcode ever returns 400 with a good token and a
     * good ratingKey, this constant is the first thing to suspect -- swap it to
     * "Chrome" to confirm before looking anywhere else.
     */
    const val PLATFORM = "Android"

    /** Set once at startup from [io.github.jackharvest.flipflex.store.Store]. */
    @Volatile
    var clientId: String = ""

    private const val CONNECT_MS = 6_000
    private const val READ_MS = 15_000

    fun headers(token: String?): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("X-Plex-Product", PRODUCT)
        put("X-Plex-Version", VERSION)
        put("X-Plex-Client-Identifier", clientId)
        put("X-Plex-Platform", PLATFORM)
        put("X-Plex-Device", DEVICE)
        put("X-Plex-Device-Name", PRODUCT)
        if (!token.isNullOrEmpty()) put("X-Plex-Token", token)
    }

    /**
     * A request that returns parsed JSON, or null on any failure.
     *
     * Null rather than an exception because every caller's response to a failed
     * Plex call is the same -- show nothing and let the user retry -- and
     * threading a Result through six screens buys nothing on a device where the
     * only recovery is "try again on better signal".
     */
    suspend fun json(
        url: String,
        token: String? = null,
        method: String = "GET",
        connectMs: Int = CONNECT_MS,
        readMs: Int = READ_MS,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val body = text(url, token, method, connectMs, readMs) ?: return@withContext null
        try {
            JSONObject(body)
        } catch (e: Exception) {
            // Plex answers XML unless Accept: application/json is honoured. If
            // this ever fires, the header got dropped somewhere -- log the head
            // of the body rather than the whole thing, which can be megabytes.
            Log.w(TAG, "not JSON from $url: ${body.take(120)}")
            null
        }
    }

    /** The same request, returning the raw body. Used where the shape varies. */
    suspend fun text(
        url: String,
        token: String? = null,
        method: String = "GET",
        connectMs: Int = CONNECT_MS,
        readMs: Int = READ_MS,
    ): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectMs
                readTimeout = readMs
                // Plex redirects between plex.direct hosts; following those is
                // fine and saves a round of manual retry logic.
                instanceFollowRedirects = true
                headers(token).forEach { (k, v) -> setRequestProperty(k, v) }
                if (method == "POST" || method == "PUT") {
                    doOutput = true
                    setFixedLengthStreamingMode(0)
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "$method $url -> HTTP $code")
                return@withContext null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            // Includes the certificate case. A handset whose clock is wrong
            // rejects every plex.direct cert; this phone gets its time from
            // NITZ over LTE, so it should not happen -- but it is worth
            // recognising in the log if it ever does.
            Log.w(TAG, "$method $url failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Status-only probe. Returns the HTTP code, or -1 if we never got one.
     *
     * Separate from [text] because the transcode preflight cares about the code
     * and not the body, and reading the body of a 200 m3u8 we are about to
     * throw away costs a round trip on a slow link.
     */
    suspend fun status(url: String, token: String? = null, timeoutMs: Int = 20_000): Int =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_MS
                    readTimeout = timeoutMs
                    headers(token).forEach { (k, v) -> setRequestProperty(k, v) }
                }
                conn.responseCode
            } catch (e: IOException) {
                Log.w(TAG, "probe $url failed: ${e.message}")
                -1
            } finally {
                conn?.disconnect()
            }
        }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
