package com.solewis.podcaster.data.repo

import com.solewis.podcaster.data.db.QueueDao
import com.solewis.podcaster.data.db.entity.QueueEntity
import com.solewis.podcaster.data.db.model.QueueItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The personal, cross-show "play next" list. Deliberately not synchronized with ExoPlayer's own
 * timeline (no `MediaController.addMediaItems`) - [nextPlayable] is consulted only at the moment
 * one episode ends or the user taps skip-to-next, and the result is played the same way as any
 * other episode ([com.solewis.podcaster.player.PlayerConnection.play]). Simpler than keeping a
 * live Room-to-ExoPlayer-timeline sync, at the cost of no gapless preloading - an acceptable
 * trade for a personal single-user app.
 */
class QueueRepository(
    private val queueDao: QueueDao,
    private val episodeRepository: EpisodeRepository,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun observeQueue(): Flow<List<QueueItem>> = queueDao.observeQueue()

    /**
     * Which episodes are in the queue, for a row to draw its queue button checked or not.
     *
     * Reported because adding to the queue showed nothing at all: the button looked the same
     * before and after, so the only way to know it had worked was to go and look at the queue.
     */
    fun observeQueuedEpisodeIds(): Flow<Set<String>> =
        observeQueue().map { items -> items.mapTo(HashSet()) { it.episodeId } }.distinctUntilChanged()

    private val _changes = MutableSharedFlow<QueueChange>(extraBufferCapacity = 1)

    /** Every toggle's outcome, so the app can confirm it - see PodcasterRoot's snackbar. */
    val changes: SharedFlow<QueueChange> = _changes.asSharedFlow()

    /**
     * Adds the episode, or takes it out if it is already there - the queue button is a toggle, so
     * a second tap is how you change your mind.
     *
     * Decided by whether the delete removed anything rather than by a separate lookup first, so
     * there is no window in which two quick taps could both read "not queued" and both add.
     */
    suspend fun toggle(episodeId: String): QueueChange {
        val change = if (queueDao.deleteByEpisodeId(episodeId) > 0) {
            QueueChange.Removed(episodeId)
        } else {
            enqueue(episodeId)
            QueueChange.Added(episodeId)
        }
        _changes.tryEmit(change)
        return change
    }

    suspend fun enqueue(episodeId: String) {
        val position = queueDao.nextPosition()
        queueDao.insert(QueueEntity(episodeId = episodeId, position = position, addedAt = now()))
    }

    suspend fun remove(queueId: Long) {
        queueDao.deleteById(queueId)
    }

    suspend fun moveUp(queueId: Long) = move(queueId, -1)

    suspend fun moveDown(queueId: Long) = move(queueId, 1)

    /** Resolves a queue row's episode to something playable, e.g. for a "play now" tap that
     * jumps the episode straight to the front rather than waiting its turn. */
    suspend fun getPlayable(episodeId: String): PlayableEpisode? = episodeRepository.getPlayableById(episodeId)

    /**
     * One-shot, fully playable snapshot of the queue in play order - used by the Android Auto
     * browse tree's "Up Next" node, which needs real [PlayableEpisode]s (with a URI) rather than
     * the [com.solewis.podcaster.data.db.model.QueueItem] display projection the Queue screen uses.
     */
    suspend fun getPlayableQueue(): List<PlayableEpisode> =
        queueDao.getAllOrdered().mapNotNull { episodeRepository.getPlayableById(it.episodeId) }

    private suspend fun move(queueId: Long, delta: Int) {
        val ordered = queueDao.getAllOrdered()
        val index = ordered.indexOfFirst { it.id == queueId }
        val newIndex = index + delta
        if (index == -1 || newIndex < 0 || newIndex >= ordered.size) return

        val reordered = ordered.toMutableList()
        reordered.add(newIndex, reordered.removeAt(index))
        reordered.forEachIndexed { i, entity -> queueDao.setPosition(entity.id, i) }
    }

    /**
     * Pops the queue's front item, if any, resolving it to something playable - falling back to
     * the next unplayed episode in [currentEpisodeId]'s own show once the queue is empty. Shared
     * by both auto-advance (on natural completion) and the manual skip-to-next action, so both
     * follow the same "your queue first, then keep going through the show" rule.
     */
    suspend fun nextPlayable(currentEpisodeId: String?): PlayableEpisode? {
        val front = queueDao.peekFront()
        if (front != null) {
            queueDao.deleteById(front.id)
            episodeRepository.getPlayableById(front.episodeId)?.let { return it }
        }
        return currentEpisodeId?.let { episodeRepository.getNextInShow(it) }
    }
}

/** What a queue toggle did, so a confirmation can say which. */
sealed interface QueueChange {
    val episodeId: String
    data class Added(override val episodeId: String) : QueueChange
    data class Removed(override val episodeId: String) : QueueChange
}
