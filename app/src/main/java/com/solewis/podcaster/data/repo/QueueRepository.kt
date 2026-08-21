package com.solewis.podcaster.data.repo

import com.solewis.podcaster.data.db.QueueDao
import com.solewis.podcaster.data.db.entity.QueueEntity
import com.solewis.podcaster.data.db.model.QueueItem
import kotlinx.coroutines.flow.Flow

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
    private val episodeRepository: EpisodeRepository
) {
    fun observeQueue(): Flow<List<QueueItem>> = queueDao.observeQueue()

    suspend fun enqueue(episodeId: String) {
        val position = queueDao.nextPosition()
        queueDao.insert(QueueEntity(episodeId = episodeId, position = position, addedAt = System.currentTimeMillis()))
    }

    suspend fun remove(queueId: Long) {
        queueDao.deleteById(queueId)
    }

    suspend fun moveUp(queueId: Long) = move(queueId, -1)

    suspend fun moveDown(queueId: Long) = move(queueId, 1)

    /** Resolves a queue row's episode to something playable, e.g. for a "play now" tap that
     * jumps the episode straight to the front rather than waiting its turn. */
    suspend fun getPlayable(episodeId: String): PlayableEpisode? = episodeRepository.getPlayableById(episodeId)

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
