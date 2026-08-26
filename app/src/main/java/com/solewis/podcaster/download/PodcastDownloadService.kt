package com.solewis.podcaster.download

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler
import com.solewis.podcaster.R
import com.solewis.podcaster.player.MediaStorage

/**
 * Runs episode downloads. Media3's [DownloadService] does the actual work - all this supplies is
 * the process-wide [DownloadManager] off the app graph, a scheduler, and the progress notification.
 *
 * `foregroundServiceType="dataSync"` in the manifest, which on Android 14+ comes with a daily
 * runtime budget and needs its own justification in the Play Console - unlike the playback
 * service's `mediaPlayback`, which has neither.
 */
@UnstableApi
class PodcastDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    /* channelDescriptionResourceId = */ 0
) {

    // Straight from [MediaStorage], not via the app graph: it is a process-wide singleton by
    // Media3's requirement, and this service can be started by the system when no container has
    // been assembled yet.
    override fun getDownloadManager(): DownloadManager = MediaStorage.downloadManager(this)

    /**
     * Without a scheduler, a download interrupted by the process dying or the network dropping just
     * stops, and nothing ever restarts it. [WorkManagerScheduler] rather than `PlatformScheduler`
     * because WorkManager is already a dependency here (the feed refresh worker), and the platform
     * one would need its own `JobService` plus `RECEIVE_BOOT_COMPLETED`.
     */
    override fun getScheduler(): Scheduler = WorkManagerScheduler(this, WORK_NAME)

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int
    ): Notification = notificationHelper.buildProgressNotification(
        /* context = */ this,
        R.drawable.ic_download,
        /* contentIntent = */ null,
        /* message = */ null,
        downloads,
        notMetRequirements
    )

    private val notificationHelper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 2
        const val CHANNEL_ID = "downloads"
        const val WORK_NAME = "podcaster-downloads"
    }
}
