package com.solewis.podcaster

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.solewis.podcaster.data.remote.HttpClient
import com.solewis.podcaster.player.PlaybackRestorer
import com.solewis.podcaster.work.RefreshAllWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Owns the single [AppContainer] instance, read from Compose via
 * [LocalAppContainer] and, once the playback service exists (Phase 4+), via
 * `application as PodcasterApp`.
 */
open class PodcasterApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /** For the one piece of startup work that outlives no particular screen - see [restoreLastPlayed]. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        if (schedulesBackgroundRefresh) {
            RefreshAllWorker.schedule(this)
        }
        restoreLastPlayed()
    }

    /**
     * Brings the mini player back after the app has been killed. Killing it takes the playback
     * service and its `ExoPlayer` down too, and the player's playlist is the only thing the UI's
     * playback state was ever built from - so without this the app reopens with no player at all,
     * even though the position was written to Room all along.
     *
     * Here rather than in a screen because playback outlives every screen: the state is ready
     * before the first frame, and an Activity recreation doesn't re-run it. Nothing is loaded or
     * buffered - see [com.solewis.podcaster.player.PlayerConnection.restore].
     */
    private fun restoreLastPlayed() {
        val restorer = PlaybackRestorer(container.episodeRepository, container.playback)
        appScope.launch { restorer.restore() }
    }

    /**
     * The one seam an instrumentation test needs. `PlaybackService` reads its repositories off
     * `application as PodcasterApp`, so a test that exercises real playback cannot substitute the
     * database or the feed host from the Activity side alone - the service would still be looking at
     * the real one. Overriding this is how both halves end up sharing a container.
     */
    open fun createContainer(): AppContainer = AppContainer(this)

    /** Off in tests: a periodic job that fetches every subscribed feed has no business firing
     * in the middle of one. */
    open val schedulesBackgroundRefresh: Boolean get() = true

    /**
     * Swaps the graph out after startup, so an instrumentation test can point the whole app -
     * Activity *and* playback service - at its own database and feed host. Application.onCreate
     * runs once per process, long before any individual test, which is why this cannot simply be a
     * constructor argument.
     */
    @VisibleForTesting
    fun installContainer(replacement: AppContainer) {
        container = replacement
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
