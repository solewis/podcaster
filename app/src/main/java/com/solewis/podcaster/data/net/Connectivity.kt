package com.solewis.podcaster.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether there is a usable connection right now.
 *
 * An interface so the offline path is reachable from a test - the one thing that cannot be arranged
 * on a JVM test is an actual lack of network.
 */
interface Connectivity {
    fun isOnline(): Boolean

    /**
     * Specifically wifi, not merely online - what a "only on wifi" setting means. A metered
     * hotspot reported as wifi by the OS still counts: the distinction users mean by "wifi only" is
     * the transport, not the billing plan.
     */
    fun isOnWifi(): Boolean
}

class AndroidConnectivity(context: Context) : Connectivity {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /**
     * Requires `VALIDATED` as well as `INTERNET`, which is the difference between "attached to a
     * network" and "that network can actually reach anything". A hotel wifi you have not signed
     * into, or a carrier connection that has dropped, both still report `INTERNET` - and treating
     * those as online is precisely how a tap on play turns into a hang.
     */
    override fun isOnline(): Boolean = capabilities()?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } ?: false

    override fun isOnWifi(): Boolean = capabilities()?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false

    private fun capabilities(): NetworkCapabilities? =
        connectivityManager?.getNetworkCapabilities(connectivityManager.activeNetwork)
}
