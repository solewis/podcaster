package com.solewis.podcaster

import android.app.Application

/**
 * Application entry point. Owns the single [AppContainer] instance, read from Compose via
 * [LocalAppContainer] and, once the playback service exists (Phase 4+), via
 * `application as PodcasterApp`.
 */
class PodcasterApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
