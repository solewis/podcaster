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
    override fun isOnline(): Boolean {
        val capabilities = connectivityManager
            ?.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
