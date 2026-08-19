package com.solewis.podcaster

import android.app.Application

/**
 * Application entry point. Once real dependencies exist (Room, OkHttp, the player), this class
 * will own a single [AppContainer] instance constructed here and read from both Compose (via a
 * CompositionLocal) and the playback service (via `application as PodcasterApp`). Increment 1
 * has no dependencies yet, so there is nothing to construct.
 */
class PodcasterApp : Application()
