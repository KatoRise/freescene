package local.freescene.freescene

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ArrayAdapter
import android.widget.Checkable
import android.widget.ListView
import android.widget.TextView

/** Native equivalent of the TV source-season picker: filled rows, check and focus outline. */
internal object PlayerListDialog {
    private var playFont: Typeface? = null

    private class ChoiceRow(context: Context, private val choice: Boolean) : TextView(context), Checkable {
        private var checked = false
        private val density = resources.displayMetrics.density
        private fun dp(value: Int) = (value * density + .5f).toInt()
        private val check = context.getDrawable(R.drawable.ic_player_selected)!!.mutate()
        fun checkColor(color: Int) { check.setTint(color) }
        override fun isChecked() = checked
        override fun setChecked(value: Boolean) { checked = value; refreshDrawableState(); invalidate() }
        override fun toggle() { setChecked(!checked) }
        override fun onCreateDrawableState(extraSpace: Int): IntArray = super.onCreateDrawableState(extraSpace + 1).also {
            if (checked) mergeDrawableStates(it, intArrayOf(android.R.attr.state_checked))
        }
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.isCheckable = choice; info.isChecked = checked
            info.className = if (choice) "android.widget.CheckedTextView" else "android.widget.Button"
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (choice) repeat(2) {
                // A square trailing area matches the visible row height. Its centered
                // icon has equal top, bottom and right insets even for wrapped labels.
                val trailing = measuredHeight - dp(6)
                if (paddingRight != trailing) {
                    setPadding(dp(16), dp(6), trailing, dp(12))
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }
            }
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (choice && checked) {
                val size = dp(16)
                val inset = (height - dp(6) - size) / 2
                check.setBounds(width - inset - size, inset, width - inset, inset + size)
                check.draw(canvas)
            }
        }
    }

    fun show(dialog: AlertDialog, selected: Int? = null) {
        dialog.show()
        val context = dialog.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val dark = context.getSharedPreferences("player-appearance", Context.MODE_PRIVATE).getBoolean("dark", true)
        val foreground = if (dark) PlayerColors.text else Color.rgb(37, 39, 49)
        val panel = if (dark) Color.rgb(39, 41, 47) else Color.rgb(227, 227, 224)
        val fill = if (dark) PlayerColors.raised else Color.WHITE
        val font = playFont ?: runCatching {
            Typeface.createFromAsset(context.assets, "flutter_assets/assets/fonts/Play-Regular.ttf")
        }.getOrElse { Typeface.create("sans-serif", Typeface.NORMAL) }.also { playFont = it }
        val window = dialog.window ?: return
        val width = minOf(dp(408), context.resources.displayMetrics.widthPixels - dp(48)).coerceAtLeast(dp(180))
        window.setBackgroundDrawable(GradientDrawable().apply { setColor(panel); cornerRadius = dp(28).toFloat() })
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        fun textStyle(view: View) {
            if (view is TextView) { view.typeface = font; view.setTextColor(foreground) }
            if (view is ViewGroup) for (i in 0 until view.childCount) textStyle(view.getChildAt(i))
        }
        textStyle(window.decorView)
        val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
        window.decorView.findViewById<TextView>(titleId)?.apply { textSize = 24f; maxLines = 2 }

        fun rowBackground(bottomGap: Int = 0) : StateListDrawable {
            fun shape(focused: Boolean, pressed: Boolean = false) = InsetDrawable(GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (pressed) { if (dark) Color.rgb(56, 58, 66) else Color.rgb(240, 240, 240) } else fill)
                if (focused) setStroke(dp(3), PlayerColors.accent)
            }, 0, 0, 0, dp(bottomGap))
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), shape(true, true))
                addState(intArrayOf(android.R.attr.state_focused), shape(true))
                addState(intArrayOf(android.R.attr.state_selected), shape(true))
                addState(intArrayOf(), shape(false))
            }
        }

        dialog.listView?.let { list ->
            val checked = list.checkedItemPosition
            val choice = list.choiceMode != ListView.CHOICE_MODE_NONE
            val items = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
            val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = (convertView as? ChoiceRow) ?: ChoiceRow(context, choice).apply {
                        typeface = font; textSize = 13f; setTextColor(foreground)
                        minHeight = dp(50) // 44 dp button + 6 dp between rows, as in the TV filter.
                        maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER_VERTICAL
                        isFocusable = false // The ListView owns D-pad movement and activation.
                        background = rowBackground(6)
                        setPadding(dp(16), dp(6), if (choice) dp(44) else dp(16), dp(12))
                        checkColor(foreground)
                    }
                    row.text = items[position]
                    row.contentDescription = items[position]
                    row.setChecked(choice && position == checked)
                    return row
                }
            }
            list.adapter = adapter
            list.divider = null; list.dividerHeight = 0
            list.selector = ColorDrawable(Color.TRANSPARENT)
            list.scrollIndicators = 0
            list.setPadding(dp(24), 0, dp(24), 0)
            val limit = minOf(dp(320), context.resources.displayMetrics.heightPixels / 2)
            var height = 0
            for (i in items.indices) {
                val row = adapter.getView(i, null, list)
                row.measure(View.MeasureSpec.makeMeasureSpec((width - dp(48)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                height += row.measuredHeight
                if (height >= limit) break
            }
            list.layoutParams = list.layoutParams.apply { this.height = minOf(height, limit) }
            if (choice && checked in items.indices) list.setItemChecked(checked, true)
            val cursor = (selected ?: checked).takeIf { it in items.indices } ?: 0
            if (TvWindow.isTv(context)) list.requestFocus()
            list.setSelection(cursor)
            // Gaining D-pad focus while the replacement adapter is laying out can
            // reset the cursor to row zero. Restore the chosen row after that layout.
            list.post { if (dialog.isShowing) list.setSelection(cursor) }
        }
        for (which in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply {
                typeface = font; textSize = 13f; isAllCaps = false
                setTextColor(foreground); backgroundTintList = null
                background = rowBackground()
                minHeight = dp(44); minimumHeight = dp(44)
                setPadding(dp(16), dp(6), dp(16), dp(6))
            }
        }
        (dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.parent as? ViewGroup)?.setPadding(dp(24), dp(16), dp(24), dp(24))
    }
}
