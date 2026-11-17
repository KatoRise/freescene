package local.freescene.freescene

import android.net.Uri
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.text.SubtitleParser

/** Keep Media3's sniffing order and all non-AVI extractors unchanged. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlayerExtractors(
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory by delegate {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(factory)
        return this
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(enabled)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
        return this
    }

    override fun setParseHagcMetadata(enabled: Boolean): ExtractorsFactory {
        delegate.setParseHagcMetadata(enabled)
        return this
    }

    private fun wrap(extractors: Array<Extractor>) = extractors.map {
        if (it.underlyingImplementation is AviExtractor) AviAc3Extractor(AviMpeg4Extractor(it)) else it
    }.toTypedArray()
}
