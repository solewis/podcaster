package com.solewis.podcaster

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.solewis.podcaster.data.remote.HttpClient

/**
 * Application entry point. Owns the single [AppContainer] instance, read from Compose via
 * [LocalAppContainer] and, once the playback service exists (Phase 4+), via
 * `application as PodcasterApp`.
 */
class PodcasterApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * Coil's default network fetcher uses its own bare OkHttpClient, which sends OkHttp's
     * default User-Agent - some podcast artwork CDNs (verified: storage.buzzsprout.com, fronted
     * by CloudFront) return a 403 for that specific string as a bot-blocking rule, even though
     * the exact same URL loads fine in a browser or with curl. [HttpClient] already carries a
     * real User-Agent for this reason (see its doc comment); reusing it here fixes artwork that
     * silently failed to load only for shows hosted on such a CDN.
     */
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(HttpClient.instance)) }
            .build()
}
