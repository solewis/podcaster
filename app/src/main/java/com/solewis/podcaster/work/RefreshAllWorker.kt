package com.solewis.podcaster.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.solewis.podcaster.PodcasterApp
import java.util.concurrent.TimeUnit

/**
 * Periodically refreshes every subscription in the background, so new episodes are already
 * there the next time the app is opened rather than only appearing after a manual pull-to-refresh
 * or opening a show. Reuses [com.solewis.podcaster.data.repo.SubscriptionRepository.refreshAll] -
 * the exact same path (and concurrency cap) the Library screen's pull-to-refresh uses.
 */
class RefreshAllWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PodcasterApp).container
        container.subscriptionRepository.refreshAll()
        // Individual per-show failures (a dead feed, a timeout) are recorded on that podcast's
        // row via recordRefreshFailure and surfaced there - not a reason to report the whole
        // periodic job as failed and have WorkManager retry it early.
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "refresh-all-subscriptions"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshAllWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
