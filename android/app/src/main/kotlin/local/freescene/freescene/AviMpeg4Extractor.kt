package local.freescene.freescene

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream

/** Preserve video bytes/decode order; supply MPEG-4 presentation timestamps to MediaCodec. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AviMpeg4Extractor(delegate: Extractor) : ForwardingExtractor(delegate) {
    private val tracks = mutableListOf<VideoTrack>()
    override fun init(output: ExtractorOutput) {
        tracks.clear()
        super.init(object : ExtractorOutput by output {
            override fun track(id: Int, type: Int): TrackOutput {
                val target = output.track(id, type)
                return if (type == C.TRACK_TYPE_VIDEO) VideoTrack(target).also { tracks.add(it) } else target
            }
        })
    }

    override fun seek(position: Long, timeUs: Long) {
        tracks.forEach { it.reset() }
        super.seek(position, timeUs)
    }

    private class VideoTrack(private val target: TrackOutput) : ForwardingTrackOutput(target) {
        private var timing: Mpeg4VopTiming? = null
        private val sample = ByteArrayOutputStream()
        private var overflow = false

        override fun format(format: Format) {
            timing = if (format.sampleMimeType == MimeTypes.VIDEO_MP4V) Mpeg4VopTiming().apply {
                format.initializationData.forEach { configure(it) }
            } else null
            sample.reset(); overflow = false
            target.format(format)
        }

        fun reset() { sample.reset(); overflow = false; timing?.reset() }

        private fun capture(bytes: ByteArray, offset: Int, size: Int, part: Int) {
            if (timing == null || overflow || size <= 0) return
            if (part != TrackOutput.SAMPLE_DATA_PART_MAIN || size > 1024 * 1024 - sample.size()) {
                overflow = true
                sample.reset()
            } else sample.write(bytes, offset, size)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
            sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, part: Int): Int =
            target.sampleData(DataReader { bytes, offset, count ->
                input.read(bytes, offset, count).also { capture(bytes, offset, it, part) }
            }, length, allowEndOfInput, part)

        override fun sampleData(data: ParsableByteArray, length: Int) =
            sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

        override fun sampleData(data: ParsableByteArray, length: Int, part: Int) {
            capture(data.data, data.position, length, part)
            target.sampleData(data, length, part)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
            val parser = timing
            val timestamp = if (parser == null) timeUs else if (overflow || offset != 0 || crypto != null || size != sample.size()) {
                parser.disable()
                timeUs
            } else parser.timestamp(sample.toByteArray(), timeUs)
            target.sampleMetadata(timestamp, flags, size, offset, crypto)
            sample.reset(); overflow = false
        }
    }
}
