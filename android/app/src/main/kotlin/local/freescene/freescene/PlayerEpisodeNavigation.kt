package local.freescene.freescene

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.media3.ui.PlayerView

/** Episode files are app navigation, not Media3's single-item playlist navigation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlayerEpisodeNavigation(private val view: PlayerView) {
    private var previousAction: (() -> Unit)? = null
    private var nextAction: (() -> Unit)? = null
    private fun replace(id: Int, action: () -> Unit): ImageButton? {
        val original = view.findViewById<ImageButton>(id) ?: return null
        val parent = original.parent as? ViewGroup ?: return null
        val position = parent.indexOfChild(original)
        val replacement = ImageButton(view.context).apply {
            this.id = id
            layoutParams = original.layoutParams
            val ru = resources.configuration.locales[0].language == "ru"
            contentDescription = if (id == androidx.media3.ui.R.id.exo_next) {
                if (ru) "Следующая серия" else "Next episode"
            } else if (ru) "Предыдущая серия" else "Previous episode"
            setImageDrawable(original.drawable?.constantState?.newDrawable(resources)?.mutate())
            imageTintList = original.imageTintList
            scaleType = original.scaleType
            setPadding(original.paddingLeft, original.paddingTop, original.paddingRight, original.paddingBottom)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { action() }
        }
        // Media3 retains the detached original. Its command/playlist updates cannot
        // briefly disable the app's focused episode button and send focus elsewhere.
        parent.removeView(original)
        parent.addView(replacement, position)
        return replacement
    }
    private val previous = replace(androidx.media3.ui.R.id.exo_prev) { previousAction?.invoke() }
    private val next = replace(androidx.media3.ui.R.id.exo_next) { nextAction?.invoke() }

    fun update(visible: Boolean, canPrevious: Boolean, canNext: Boolean, previous: () -> Unit, next: () -> Unit) {
        previousAction = previous; nextAction = next
        fun apply(button: ImageButton?, enabled: Boolean) {
            button?.visibility = if (visible) View.VISIBLE else View.GONE
            button?.isEnabled = visible && enabled
            button?.alpha = if (visible && enabled) 1f else .35f
        }
        apply(this.previous, canPrevious)
        apply(this.next, canNext)
    }
}
