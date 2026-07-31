package com.github.jackharvest.flipflex.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.github.jackharvest.flipflex.R
import com.github.jackharvest.flipflex.input.Action
import com.github.jackharvest.flipflex.plex.PlexAuth
import com.github.jackharvest.flipflex.plex.PlexServers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sign in with a four-character code at plex.tv/link.
 *
 * There is no password entry anywhere in FlipFlex and there should never be.
 * This handset has a T9 keypad, no touch keyboard and no password manager;
 * asking someone to enter a real Plex password on it would be both miserable
 * and worse for their security than displaying a throwaway code.
 */
class LinkActivity : FlipActivity() {

    private lateinit var codeView: TextView
    private lateinit var statusView: TextView
    private var poll: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = layoutInflater.inflate(R.layout.activity_link, null)
        setBody(body)
        codeView = body.findViewById(R.id.link_code)
        statusView = body.findViewById(R.id.link_status)

        setHeader("Sign in", showChevron = false)
        // Neither softkey does anything here. There is no Home to go to before
        // sign-in, and no item to open Options on -- so the bar stays blank
        // rather than advertising keys that do nothing.
        setSoftKeys(left = null, right = null)

        start()
    }

    private fun start() {
        poll?.cancel()
        poll = lifecycleScope.launch {
            codeView.text = "····"
            statusView.text = getString(R.string.msg_loading)

            val pin = PlexAuth.newPin()
            if (pin == null) {
                statusView.text = getString(R.string.link_failed)
                return@launch
            }
            codeView.text = pin.code
            statusView.text = getString(R.string.link_waiting)

            // Plex expires a link PIN after 15 minutes. Polling past that just
            // burns radio -- give up and offer a fresh code instead, which is
            // what the user would have to ask for anyway.
            val deadline = System.currentTimeMillis() + 15 * 60_000
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(2_000)
                val token = PlexAuth.checkPin(pin.id) ?: continue

                store.token = token
                // Recorded separately, and this is the token profile switching
                // uses later -- see Store.homeToken.
                store.homeToken = token
                statusView.text = getString(R.string.msg_loading)

                // Pick a server before leaving, so Home opens onto content
                // rather than onto its own loading state.
                PlexServers.pick(token)?.let {
                    store.serverUri = it.uri
                    store.serverToken = it.token
                    store.serverName = it.name
                    store.serverClientId = it.serverId
                }
                startActivity(Intent(this@LinkActivity, HomeActivity::class.java))
                finish()
                return@launch
            }
            statusView.text = getString(R.string.link_expired)
        }
    }

    override fun onAction(action: Action, keyCode: Int): Boolean = when (action) {
        // OK restarts the whole flow. It is the only control on this screen, so
        // it has to cover both "the request failed" and "the code went stale".
        Action.SELECT -> { start(); true }
        else -> false
    }

    override fun onDestroy() {
        poll?.cancel()
        super.onDestroy()
    }
}
