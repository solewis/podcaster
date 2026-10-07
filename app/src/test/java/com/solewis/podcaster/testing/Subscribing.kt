package com.solewis.podcaster.testing

import com.solewis.podcaster.data.repo.SubscribeResult
import com.solewis.podcaster.data.repo.SubscriptionRepository

/**
 * Subscribes and then waits for the episodes, which [SubscriptionRepository.subscribe] does not - it
 * returns as soon as the show is added. For tests about what comes after a show has loaded, which
 * otherwise race its load: a refresh issued straight away joins it rather than making its own
 * request.
 */
suspend fun SubscriptionRepository.subscribeAndLoad(
    feedUrl: String,
    seedTitle: String? = null
): SubscribeResult {
    val result = subscribe(feedUrl, seedTitle = seedTitle)
    if (result is SubscribeResult.Success) awaitLoad(result.podcastId)
    return result
}
