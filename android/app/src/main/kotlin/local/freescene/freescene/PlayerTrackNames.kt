package local.freescene.freescene

import android.content.res.Resources
import androidx.media3.common.Format
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.TrackNameProvider

/** Keep container titles (e.g. dubbing studios) alongside Media3's localized details. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlayerTrackNames(private val resources: Resources) : TrackNameProvider {
    private val fallback = DefaultTrackNameProvider(resources)
    override fun getTrackName(format: Format): String {
        val label = format.label?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.trim()?.take(160).orEmpty()
        val details = fallback.getTrackName(format).replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
        return if (label.isEmpty() || details.startsWith(label, ignoreCase = true)) details
            else "$label · $details"
    }

    fun audioTrackName(format: Format, number: Int): String {
        val name = getTrackName(format)
        val language = format.language?.trim()?.lowercase().orEmpty()
        val label = format.label?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.trim().orEmpty()
        if (language.isNotEmpty() && language.substringBefore('-') != "und") return name
        // Labels may themselves contain the only available language or dubbing name.
        if (label.isNotEmpty() && !label.equals("Stereo", true) && !label.equals("Стерео", true)) return name
        val ru = resources.configuration.locales[0].language == "ru"
        return if (ru) "Дорожка $number · Язык не указан · $name"
            else "Track $number · Language unspecified · $name"
    }
}
