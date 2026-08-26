package com.solewis.podcaster.player

import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.settings.SettingsStore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chosen playback speed used to live only inside `ExoPlayer`, so every process death reset it to
 * 1x. These two classes are what carries it across now: [SpeedPersister] writes it down when it
 * changes, [SettingsStore] hands it back when the player is rebuilt.
 *
 * The durability of `SharedPreferences` itself is not the point here - the failure these tests
 * exist to catch is the silent one, where getter and setter disagree about a key (or the listener
 * is wired to a callback that never fires) and the setting reads back as the default forever.
 */
@RunWith(AndroidJUnit4::class)
class SpeedPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun speed_is_normal_until_something_chooses_otherwise() {
        assertThat(SettingsStore(context).speed).isEqualTo(1f)
    }

    @Test
    fun a_chosen_speed_is_read_back_by_a_separate_reader() {
        SettingsStore(context).speed = 1.5f

        // A separate instance, because the player is built by the service while the number shown
        // on Now Playing is read by the UI - two readers that have to agree.
        assertThat(SettingsStore(context).speed).isEqualTo(1.5f)
    }

    @Test
    fun the_persister_records_whatever_changed_the_players_speed() {
        val settings = SettingsStore(context)

        SpeedPersister(settings).onPlaybackParametersChanged(PlaybackParameters(1.8f))

        assertThat(settings.speed).isEqualTo(1.8f)
    }
}
