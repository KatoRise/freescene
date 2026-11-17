package local.freescene.freescene

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.Checkable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerListDialogTest {
    @Test fun distantSelectionSurvivesStylingAndFocusMovesWithoutChangingTheChoice() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var chosen = -1
        val labels = Array(40) { "Episode ${it + 1}" }
        val dialog = AlertDialog.Builder(activity.get())
            .setTitle("Episodes").setSingleChoiceItems(labels, 25) { d, i -> chosen = i; d.dismiss() }
            .setNegativeButton("Close", null).create()
        PlayerListDialog.show(dialog)
        shadowOf(Looper.getMainLooper()).idle()
        val list = dialog.listView
        assertEquals(25, list.checkedItemPosition)
        assertEquals(25, list.selectedItemPosition)
        list.setSelection(26)
        assertEquals(25, list.checkedItemPosition)
        assertTrue((list.adapter.getView(25, null, list) as Checkable).isChecked)
        assertFalse((list.adapter.getView(26, null, list) as Checkable).isChecked)
        list.performItemClick(null, 26, 26)
        assertEquals(26, chosen)
        assertFalse(dialog.isShowing)
        activity.pause().stop().destroy()
    }
}
