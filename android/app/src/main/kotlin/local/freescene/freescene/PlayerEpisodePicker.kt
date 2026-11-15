package local.freescene.freescene

import android.app.AlertDialog
import android.content.Context

/** Two separate pickers: seasons, then only the episodes in the chosen season. */
internal class PlayerEpisodePicker(
    private val context: Context,
    labels: List<String>,
    private val selected: Int,
    private val series: Boolean,
    private val chosen: (Int) -> Unit,
    private val cancelled: () -> Unit,
    private val dismissed: () -> Unit,
) {
    internal data class Entry(val index: Int, val label: String, val season: Int?, val episode: Int?)
    private val entries = labels.mapIndexed { index, label -> parse(index, label) }
    private val seasons = entries.map { it.season }.distinct().sortedBy { it ?: Int.MAX_VALUE }
    private var season = if (selected in entries.indices) entries[selected].season else seasons.firstOrNull()
    private var dialog: AlertDialog? = null
    private var closed = false
    private val ru get() = context.resources.configuration.locales[0].language == "ru"
    private fun text(ru: String, en: String) = if (this.ru) ru else en

    fun show() {
        if (series && seasons.size > 1) showSeasons() else showEpisodes()
    }

    fun dismiss() {
        if (closed) return
        closed = true
        val old = dialog; dialog = null
        old?.dismiss()
        dismissed()
    }

    private fun cancel() { dismiss(); cancelled() }

    private fun display(next: AlertDialog, cursor: Int) {
        val old = dialog
        dialog = next
        old?.dismiss()
        next.setOnDismissListener { if (dialog === next) dismiss() }
        next.setOnCancelListener { cancel() }
        PlayerListDialog.show(next, cursor)
    }

    private fun builder(title: String) = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(title).setNegativeButton(text("Закрыть", "Close")) { _, _ -> cancel() }

    private fun showSeasons() {
        val labels = seasons.map { value ->
            value?.let { text("Сезон $it", "Season $it") } ?: text("Сезон не указан", "Season unspecified")
        }
        val current = seasons.indexOf(season)
        display(builder(text("Сезоны", "Seasons"))
            .setSingleChoiceItems(labels.toTypedArray(), current) { _, which ->
                season = seasons[which]
                showEpisodes()
            }.create(), current)
    }

    private fun showEpisodes() {
        val values = (if (series) entries.filter { it.season == season } else entries)
            .sortedWith(compareBy<Entry> { it.episode ?: Int.MAX_VALUE }.thenBy { it.index })
        val labels = values.map { entry ->
            if (!series || entry.episode == null) entry.label.substringAfterLast('/') else {
                val label = text("Серия ${entry.episode}", "Episode ${entry.episode}")
                if (values.count { it.episode == entry.episode } > 1) "$label · ${entry.label.substringAfterLast('/')}" else label
            }
        }
        val current = values.indexOfFirst { it.index == selected }
        val builder = builder(if (series) text("Серии", "Episodes") else text("Видео", "Videos"))
            .setSingleChoiceItems(labels.toTypedArray(), current) { _, which ->
                val index = values[which].index
                dismiss()
                chosen(index)
            }
        if (series && seasons.any { it != null }) builder.setNeutralButton(text("Сезоны", "Seasons")) { _, _ -> showSeasons() }
        display(builder.create(), current)
    }

    companion object {
        private val combined = Regex("(?iu)(?<![a-z0-9])s([0-9]{1,3})[ ._-]*e([0-9]{1,4})(?![0-9])")
        private val crossed = Regex("(?iu)(?<![0-9])([0-9]{1,3})x([0-9]{1,4})(?![0-9])")
        private val seasonPrefix = Regex("(?iu)(?:season|сезон)[ ._-]*([0-9]{1,3})(?![0-9])")
        private val seasonSuffix = Regex("(?iu)(?<![0-9])([0-9]{1,3})[ ._-]*(?:season|сезон)")
        private val shortSeason = Regex("(?iu)(?<![a-z0-9])s([0-9]{1,3})(?![a-z0-9])")
        private val episodePrefix = Regex("(?iu)(?:episode|серия|эпизод|(?<![a-z])e)[ ._-]*([0-9]{1,4})(?![0-9])")
        private val episodeSuffix = Regex("(?iu)(?<![0-9])([0-9]{1,4})[ ._-]*(?:серия|episode|эпизод)")
        internal fun parse(index: Int, label: String): Entry {
            val file = label.substringAfterLast('/')
            val pair = combined.find(file) ?: crossed.find(file)
            if (pair != null) return Entry(index, label, pair.groupValues[1].toInt(), pair.groupValues[2].toInt())
            fun number(patterns: List<Regex>, value: String): Int? = patterns.firstNotNullOfOrNull { it.find(value)?.groupValues?.get(1)?.toIntOrNull() }
            val seasonPatterns = listOf(seasonPrefix, seasonSuffix, shortSeason)
            val season = number(seasonPatterns, file) ?: number(seasonPatterns, label.substringBeforeLast('/', ""))
            val episode = number(listOf(episodePrefix, episodeSuffix), file)
                ?: if (season != null) Regex("^([0-9]{1,4})(?:[ ._-]|$)").find(file)?.groupValues?.get(1)?.toIntOrNull() else null
            return Entry(index, label, season, episode)
        }
    }
}
