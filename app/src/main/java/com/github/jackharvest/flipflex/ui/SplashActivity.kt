package com.github.jackharvest.flipflex.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.dl.DownloadService
import com.github.jackharvest.flipflex.dl.Downloads
import com.github.jackharvest.flipflex.plex.PlexAuth
import com.github.jackharvest.flipflex.plex.PlexClient
import com.github.jackharvest.flipflex.plex.PlexServers
import com.github.jackharvest.flipflex.store.Store
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

        // Before anything else, and deliberately before the network calls
        // below. A row left saying `downloading` is what a crash, a battery
        // pull or `adb install -r` over a running build leaves behind, and the
        // service only ever picks up `queued` -- so without this the download
        // would sit there looking active and never move again.
        Downloads.recover(this)

        lifecycleScope.launch {
            if (!store.isLinked) {
                go(LinkActivity::class.java)
                return@launch
            }

            status.text = getString(R.string.msg_loading)

            // Validate before trusting the stored token. A token revoked from
            // plex.tv -- "sign out all devices" -- otherwise fails much later,
            // as an empty library that looks like a server problem.
            //
            // Only Rejected signs out. Unreachable means we could not ask, and
            // discarding a login because the radio is off is how this used to
            // strand someone on a train with a phone full of downloads and a
            // sign-in screen they need a second device to get past.
            when (val v = PlexAuth.validate(store.token!!)) {
                is PlexAuth.Validation.Rejected -> {
                    store.signOut()
                    go(LinkActivity::class.java)
                    return@launch
                }
                is PlexAuth.Validation.Unreachable -> {
                    // Straight to Home, which draws the offline screen -- the
                    // Downloads library, or a retry prompt if there is nothing
                    // saved. Picking a server would only spend another set of
                    // connect timeouts arriving at the same place.
                    go(HomeActivity::class.java)
                    return@launch
                }
                is PlexAuth.Validation.Ok -> status.text = v.name
            }

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
            // Anything queued from a previous run, now that there is a server
            // to fetch it from. The service checks the Wi-Fi-only setting
            // itself and stops immediately if there is nothing to do.
            if (chosen != null && Downloads.nextQueued(this@SplashActivity) != null) {
                DownloadService.start(this@SplashActivity)
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
