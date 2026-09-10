package com.solewis.podcaster.player

import androidx.media3.common.PlaybackException

/**
 * Errors worth automatically retrying rather than giving up on immediately - the buffer running
 * dry with no network left to refill it, as opposed to a genuinely broken or missing file.
 *
 * Shared between [PlayerConnection] (which decides what the UI shows) and [PlaybackErrorRetrier]
 * (which decides whether to actually retry), so the two cannot silently disagree about what counts
 * as "just a connection problem."
 */
fun PlaybackException.isRecoverableNetworkError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
)
