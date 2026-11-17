package local.freescene.freescene

import android.app.Activity
import android.os.Looper
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerEpisodeNavigationTest {
    @Test fun stockNavigationDisablesTheAppEnabledNextButtonOnRefresh() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = PlayerView(activity.get())
        activity.get().setContentView(view)
        view.setControllerAnimationEnabled(false); view.showController()
        val next = view.findViewById<View>(androidx.media3.ui.R.id.exo_next)
        next.isEnabled = true
        view.setShowNextButton(true)
        assertFalse(next.isEnabled)
        activity.pause().stop().destroy()
    }

    @Test fun episodeFocusSurvivesMedia3RefreshesPlayingAndPausedAndRealBoundariesStillDisable() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = PlayerView(activity.get())
        activity.get().setContentView(view)
        view.setControllerAnimationEnabled(false)
        val navigation = PlayerEpisodeNavigation(view)
        val player = ExoPlayer.Builder(activity.get()).build()
        view.player = player
        var forward = 0
        fun update() = navigation.update(true, true, true, {}, { forward++ })
        update(); view.showController()
        val next = view.findViewById<View>(androidx.media3.ui.R.id.exo_next)
        next.isFocusableInTouchMode = true
        assertTrue(next.requestFocus())
        var lostFocus = 0
        next.setOnFocusChangeListener { _, focused -> if (!focused) lostFocus++ }
        for (playing in listOf(true, false)) {
            player.playWhenReady = playing
            repeat(4) {
                view.setShowNextButton(true); view.setShowPreviousButton(true)
                update(); shadowOf(Looper.getMainLooper()).idle()
                assertTrue(next.isEnabled); assertTrue(next.hasFocus())
            }
        }
        assertEquals(0, lostFocus)
        next.performClick(); assertEquals(1, forward)
        navigation.update(true, true, false, {}, {})
        assertFalse(next.isEnabled)
        view.player = null; player.release()
        activity.pause().stop().destroy()
    }
}
