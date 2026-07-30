package io.github.jackharvest.flipflex.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.jackharvest.flipflex.R
import io.github.jackharvest.flipflex.plex.PlexAuth
import io.github.jackharvest.flipflex.plex.PlexClient
import io.github.jackharvest.flipflex.plex.PlexServers
import io.github.jackharvest.flipflex.store.Store
import kotlinx.coroutines.launch

/**
 * Decide where the user should land, while the logo is on screen.
 *
 * Not a timed splash. The image is shown for exactly as long as the work behind
 * it takes -- validating the stored token and finding a reachable server -- and
 * both of those are network calls that can take several seconds on this
 * handset. Putting them behind the logo is the difference between a considered
 * start-up and a black screen.
 *
 * This is deliberately not a [FlipActivity]: there is nothing to navigate, no
 * softkey does anything useful, and inheriting the shell would draw an empty
 * `Home | Options` bar over the artwork.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        val status = findViewById<TextView>(R.id.splash_status)
        val store = Store(this)
        PlexClient.clientId = store.clientId

        lifecycleScope.launch {
            if (!store.isLinked) {
                go(LinkActivity::class.java)
                return@launch
            }

            status.text = getString(R.string.msg_loading)

            // Validate before trusting the stored token. A token revoked from
            // plex.tv -- "sign out all devices" -- otherwise fails much later,
            // as an empty library that looks like a server problem.
            val who = PlexAuth.validate(store.token!!)
            if (who == null) {
                store.signOut()
                go(LinkActivity::class.java)
                return@launch
            }
            status.text = who

            // Re-pick the connection on every launch rather than trusting the
            // stored one. The stored URI is a LAN address most of the time, and
            // this phone leaves the house -- a cached 192.168.x address costs a
            // full connect timeout before anything can happen.
            val chosen = PlexServers.pick(store.token!!, store.serverClientId)
            if (chosen != null) {
                store.serverUri = chosen.uri
                store.serverToken = chosen.token
                store.serverName = chosen.name
                store.serverClientId = chosen.serverId
            }
            go(HomeActivity::class.java)
        }
    }

    private fun go(target: Class<*>) {
        startActivity(Intent(this, target))
        // finish() rather than letting it stack: the back arrow from Home
        // should leave the app, not redisplay a splash that immediately
        // forwards again.
        finish()
    }
}
