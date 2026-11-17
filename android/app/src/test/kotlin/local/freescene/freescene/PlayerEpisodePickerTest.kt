package local.freescene.freescene

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerEpisodePickerTest {
    @Test fun seasonsAndEpisodesAreSeparateAndSelectionsMapBackToTheOriginalFile() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var chosen = -1; var dismissals = 0; var cancellations = 0
        val picker = PlayerEpisodePicker(activity.get(), listOf("Show.S02E02.mkv", "Show.S01E01.avi", "Show.S02E01.mkv"),
            selected = 1, series = true, chosen = { chosen = it }, cancelled = { cancellations++ }, dismissed = { dismissals++ })
        picker.show()
        val seasons = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(2, seasons.listView.count)
        assertEquals(0, seasons.listView.checkedItemPosition)
        seasons.listView.performItemClick(null, 1, 1)
        shadowOf(Looper.getMainLooper()).idle()
        val episodes = ShadowAlertDialog.getLatestAlertDialog()
        assertNotSame(seasons, episodes)
        assertFalse(seasons.isShowing)
        assertEquals(2, episodes.listView.count)
        assertEquals(-1, episodes.listView.checkedItemPosition)
        assertEquals(0, dismissals)
        episodes.listView.performItemClick(null, 0, 0)
        assertEquals(2, chosen)
        assertEquals(1, dismissals)
        assertEquals(0, cancellations)
        activity.pause().stop().destroy()
    }

    @Test fun oneSeasonOpensEpisodesAndTheSeasonsListIsStillAvailable() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var dismissals = 0; var cancellations = 0
        val picker = PlayerEpisodePicker(activity.get(), listOf("S08E01.avi", "S08E02.avi"), 1, true,
            chosen = { fail("Cancellation must not start an episode") }, cancelled = { cancellations++ }, dismissed = { dismissals++ })
        picker.show()
        val episodes = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(2, episodes.listView.count)
        assertEquals(1, episodes.listView.checkedItemPosition)
        episodes.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val seasons = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(1, seasons.listView.count)
        assertEquals(0, dismissals)
        seasons.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, cancellations); assertEquals(1, dismissals)
        picker.dismiss(); assertEquals(1, dismissals)
        activity.pause().stop().destroy()
    }

    @Test fun onlyExplicitSeasonAndEpisodeNumbersAreUsed() {
        for ((label, season, episode) in listOf(
            Triple("The.Rookie.S08E01.720p.avi", 8, 1),
            Triple("Show/Season 2/03 - title.mkv", 2, 3),
            Triple("Сезон 3/4 серия.avi", 3, 4),
            Triple("Title 2x04.avi", 2, 4),
            Triple("Show.2026.1080p.mkv", null, null),
            Triple("Серия 8", null, 8))) {
            val value = PlayerEpisodePicker.parse(0, label)
            assertEquals(label, season, value.season)
            assertEquals(label, episode, value.episode)
        }
    }
}
