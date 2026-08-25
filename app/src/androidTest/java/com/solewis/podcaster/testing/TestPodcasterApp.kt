package com.solewis.podcaster.testing

import com.solewis.podcaster.AppContainer
import com.solewis.podcaster.PodcasterApp

/**
 * The [PodcasterApp] instrumentation tests run against, installed by [PodcasterTestRunner].
 *
 * Its only jobs are to keep the periodic refresh worker from firing mid-test, and to start from an
 * in-memory database so no test can see - or damage - the library on the device. Tests that need
 * specific content install their own container over the top via `installContainer`.
 */
class TestPodcasterApp : PodcasterApp() {

    override val schedulesBackgroundRefresh: Boolean get() = false

    override fun createContainer(): AppContainer =
        AppContainer(this, database = inMemoryTestDatabase(this))
}
