package com.github.jackharvest.flipflex.store

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * What the radio is doing, in the only three states the app cares about.
 *
 * ## Why three, and why "metered" rather than "mobile data"
 *
 * `NET_CAPABILITY_NOT_METERED` rather than `TRANSPORT_WIFI`, because a metered
 * hotspot is a phone plan wearing a Wi-Fi hat and the whole point of the setting
 * is not to spend somebody's allowance on a film.
 *
 * [NONE] exists separately from [METERED] because the difference matters at
 * every call site and collapsing them gets one of them wrong. A download queue
 * with no network should wait, exactly as it waits for Wi-Fi. But warning
 * someone that they are "on mobile data" when the phone has no connection at
 * all is a dialog that is simply untrue, in front of a playback that was going
 * to fail with a network error either way.
 */
object Net {

    enum class Link { NONE, UNMETERED, METERED }

    fun link(ctx: Context): Link {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return Link.NONE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Link.NONE
        // A network that has not validated yet -- this handset's Wi-Fi spends
        // eighteen seconds there before dropping itself, see docs -- is still a
        // network as far as metering goes.
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            Link.UNMETERED
        } else {
            Link.METERED
        }
    }

    /** True only when there is a link and it is free to use. */
    fun unmetered(ctx: Context): Boolean = link(ctx) == Link.UNMETERED

    /** True only when there is a link and it costs money. */
    fun metered(ctx: Context): Boolean = link(ctx) == Link.METERED
}
