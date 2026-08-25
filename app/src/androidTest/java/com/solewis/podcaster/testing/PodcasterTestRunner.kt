package com.solewis.podcaster.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Substitutes [TestPodcasterApp] for the real Application, so instrumentation tests never touch
 * the on-disk database the app itself uses.
 *
 * This is what makes device tests trustworthy. `MainActivitySmokeTest` previously read the real
 * shared library, so it failed the moment a subscription was added by hand - a test that fails for
 * reasons unrelated to the change under test teaches you to ignore it.
 */
class PodcasterTestRunner : AndroidJUnitRunner() {

    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestPodcasterApp::class.java.name, context)
}
