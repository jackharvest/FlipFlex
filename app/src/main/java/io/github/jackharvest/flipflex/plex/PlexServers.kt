package io.github.jackharvest.flipflex.plex

import android.util.Log
import org.json.JSONArray
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finding a server to talk to, and picking the connection that will actually
 * work from where the phone is standing.
 *
 * The ordering here is the whole value of this file. `/api/v2/resources` hands
 * back every connection Plex knows about for every server on the account, and a
 * dead candidate costs a multi-second timeout each. Worse, the list routinely
 * includes LAN addresses belonging to *other people's* networks -- a server
 * shared with you advertises its `192.168.1.x` address, which from here is
 * either unroutable or, occasionally, someone else's device entirely. Probing
 * that list in the order Plex returns it is how a 30-second startup happens.
 */
object PlexServers {

    private const val TAG = "FlipFlex/servers"

    data class Connection(
        val serverId: String,
        val name: String,
        val uri: String,
        val token: String,
        val local: Boolean,
        val relay: Boolean,
    )

    data class Chosen(val uri: String, val token: String, val name: String, val serverId: String)

    /**
     * Every connection on the account, best-first.
     *
     * Rank 0 = a local address on our own /24, 1 = local elsewhere, 2 = remote,
     * 3 = Plex Relay. Relay is last because it is bandwidth-capped at 1 Mbps
     * and proxied through Plex's infrastructure -- it works, but transcoding a
     * video down that pipe is the worst experience of the four.
     */
    suspend fun connections(accountToken: String, wantServerId: String? = null): List<Connection> {
        val body = PlexClient.text(
            "${PlexClient.PLEX_TV}/resources?includeHttps=1&includeRelay=1",
            accountToken,
        ) ?: return emptyList()

        val arr = try {
            JSONArray(body)
        } catch (e: Exception) {
            Log.w(TAG, "resources was not an array: ${body.take(120)}")
            return emptyList()
        }

        val mine = lanIp()
        val out = mutableListOf<Pair<Int, Connection>>()

        for (i in 0 until arr.length()) {
            val res = arr.optJSONObject(i) ?: continue
            if (!res.str("provides").contains("server")) continue
            val serverId = res.str("clientIdentifier")
            if (wantServerId != null && serverId != wantServerId) continue

            val name = res.str("name", "Plex Server")
            // A shared server answers only to the access token that came with
            // it here. Falling back to the account token for those produces a
            // 401 that looks exactly like an expired login.
            //
            // strOrNull, not optString: `accessToken` is explicitly null for a
            // server you own yourself, which is exactly the JSONObject.NULL
            // case Json.kt exists for. optString would put the four-character
            // string "null" into the X-Plex-Token header.
            val token = res.strOrNull("accessToken") ?: accountToken
            val conns = res.optJSONArray("connections") ?: continue

            for (j in 0 until conns.length()) {
                val c = conns.optJSONObject(j) ?: continue
                val uri = c.str("uri")
                if (uri.isEmpty()) continue
                val local = c.optBoolean("local", false)
                val relay = c.optBoolean("relay", false)
                out += rank(uri, local, relay, mine) to
                    Connection(serverId, name, uri, token, local, relay)
            }
        }

        return out.sortedBy { it.first }.map { it.second }
    }

    /**
     * Walk the candidates in order and return the first that answers.
     *
     * Every candidate gets a second chance over plain http on the same host
     * before we move on. That is not laziness about TLS -- Plex issues
     * certificates for hostnames like `10-0-0-4.<hash>.plex.direct`, which only
     * work if the phone's DNS will resolve them. A captive or minimal DNS
     * resolver on a phone network returns NXDOMAIN for those, and the fallback
     * to the literal IP is the difference between "works" and "no servers
     * found" on an otherwise perfectly good LAN.
     */
    suspend fun pick(accountToken: String, wantServerId: String? = null): Chosen? {
        for (c in connections(accountToken, wantServerId)) {
            if (probe(c.uri, c.token)) {
                return Chosen(c.uri, c.token, c.name, c.serverId)
            }
            val plain = plainUri(c.uri) ?: continue
            if (probe(plain, c.token)) {
                Log.i(TAG, "plex.direct unreachable for ${c.uri}; using $plain")
                return Chosen(plain, c.token, c.name, c.serverId)
            }
        }
        return null
    }

    /** A short-timeout liveness check. `/identity` needs no auth but is cheap. */
    suspend fun probe(uri: String, token: String): Boolean {
        val o = PlexClient.json("$uri/identity", token, connectMs = 4_000, readMs = 6_000)
        return o?.optJSONObject("MediaContainer")?.has("machineIdentifier") == true
    }

    private fun rank(uri: String, local: Boolean, relay: Boolean, mine: String?): Int {
        if (relay) return 3
        if (!local) return 2
        val theirs = uriIp(uri)
        return if (mine != null && theirs != null &&
            mine.substringBeforeLast('.') == theirs.substringBeforeLast('.')
        ) 0 else 1
    }

    /**
     * Recover the literal IPv4 address out of a plex.direct hostname.
     *
     * `https://10-0-0-4.abc123.plex.direct:32400` encodes `10.0.0.4`. Having it
     * lets us rank same-subnet servers first without probing anything, and gives
     * us the http fallback above.
     */
    fun uriIp(uri: String): String? =
        Regex("""^https?://(\d{1,3})-(\d{1,3})-(\d{1,3})-(\d{1,3})\.""")
            .find(uri)
            ?.let { m -> (1..4).joinToString(".") { m.groupValues[it] } }

    private fun plainUri(uri: String): String? {
        if (uri.startsWith("http://")) return null // already plain; nothing to fall back to
        val ip = uriIp(uri) ?: return null
        val port = Regex(""":(\d+)$""").find(uri)?.groupValues?.get(1) ?: "32400"
        return "http://$ip:$port"
    }

    /**
     * Our own IPv4 address on a real LAN.
     *
     * Wi-Fi only, on purpose. This phone keeps a SIM in it and sits on LTE by
     * default -- `ccmni0` currently holds a public 48.x address -- and a
     * carrier address must never be compared against a server's `192.168.x`
     * subnet. Skipping non-wlan interfaces makes the same-subnet rank mean what
     * it says, and correctly returns null on cellular, where no server is local.
     */
    private fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        Log.w(TAG, "could not read local address: ${e.message}")
        null
    }
}
