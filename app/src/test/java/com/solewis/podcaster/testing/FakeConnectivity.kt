package com.solewis.podcaster.testing

import com.solewis.podcaster.data.net.Connectivity

/**
 * Online unless a test says otherwise. The one condition a JVM test cannot actually arrange is the
 * absence of a network, which is exactly the condition worth covering.
 */
class FakeConnectivity(var online: Boolean = true) : Connectivity {
    override fun isOnline(): Boolean = online
}
