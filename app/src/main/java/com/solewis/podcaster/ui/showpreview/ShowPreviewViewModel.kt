package com.solewis.podcaster.ui.showpreview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.repo.PlayableEpisode
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.ShowPreview
import com.solewis.podcaster.data.repo.ShowPreviewRepository
import com.solewis.podcaster.data.repo.SubscribeResult
import com.solewis.podcaster.data.repo.SubscriptionRepository
import com.solewis.podcaster.domain.FeedToEpisodesMapper
import com.solewis.podcaster.domain.HtmlToText
import com.solewis.podcaster.player.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs a show that hasn't been subscribed to yet, so it holds no Room id for the podcast or its
 * episodes - everything comes straight from the live [ShowPreview] fetch. Playback uses the raw
 * `stableKey` (not the `"$podcastId:..."` primary key [com.solewis.podcaster.domain.EpisodeIdentity]
 * builds for a subscribed show) since there is no podcast row yet to prefix it with.
 */
class ShowPreviewViewModel(
    private val feedUrl: String,
    private val itunesCollectionId: Long?,
    private val seedTitle: String?,
    private val seedArtworkUrl: String?,
    private val showPreviewRepository: ShowPreviewRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val podcastRepository: PodcastRepository,
    private val playerConnection: PlayerConnection
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val error: String? = null,
        val preview: ShowPreview? = null,
        val isSubscribing: Boolean = false,
        val subscribedPodcastId: Long? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Reached here from a Search row that doesn't know the true subscription state
            // (e.g. subscribed moments ago from a different row for the same show - iTunes can
            // return the same feedUrl under more than one catalog entry). Redirecting immediately
            // avoids showing a stale "not subscribed yet" preview - and the Subscribe button
            // briefly, confusingly appearing to do nothing when tapped, since subscribing again
            // is a harmless no-op that was never going to flip it to "Subscribed".
            val existing = podcastRepository.findByFeedUrl(feedUrl)
            if (existing != null) {
                _state.value = _state.value.copy(isLoading = false, subscribedPodcastId = existing.id)
                return@launch
            }

            val preview = showPreviewRepository.load(feedUrl, seedTitle)
            _state.value = if (preview == null) {
                _state.value.copy(isLoading = false, error = "Couldn't load this show")
            } else {
                _state.value.copy(isLoading = false, preview = preview)
            }
        }
    }

    fun play(episode: FeedToEpisodesMapper.MappedEpisode) {
        val preview = _state.value.preview ?: return
        viewModelScope.launch {
            playerConnection.play(
                PlayableEpisode(
                    episodeId = episode.stableKey,
                    title = episode.title,
                    podcastTitle = preview.title,
                    artworkUrl = episode.artworkUrl ?: preview.artworkUrl,
                    mediaUrl = episode.enclosureUrl,
                    startPositionMillis = 0L
                )
            )
        }
    }

    fun descriptionFor(episode: FeedToEpisodesMapper.MappedEpisode): String? =
        HtmlToText.toPlainText(episode.descriptionHtml)

    fun subscribe() {
        val preview = _state.value.preview ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSubscribing = true, error = null)
            val outcome = subscriptionRepository.subscribe(
                feedUrl = feedUrl,
                itunesCollectionId = itunesCollectionId,
                seedTitle = preview.title,
                seedArtworkUrl = preview.artworkUrl
            )
            _state.value = when (outcome) {
                is SubscribeResult.Success -> _state.value.copy(isSubscribing = false, subscribedPodcastId = outcome.podcastId)
                is SubscribeResult.AlreadySubscribed -> _state.value.copy(isSubscribing = false, subscribedPodcastId = outcome.podcastId)
                is SubscribeResult.Failure -> _state.value.copy(isSubscribing = false, error = outcome.message)
            }
        }
    }
}
