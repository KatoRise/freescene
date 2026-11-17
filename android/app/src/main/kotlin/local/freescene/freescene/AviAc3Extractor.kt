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
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import java.io.ByteArrayOutputStream
import java.io.EOFException

/** AVI chunks are not AC3 access units: one chunk may hold several frames or part of one. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AviAc3Extractor(delegate: Extractor) : ForwardingExtractor(delegate) {
    private val audioTracks = mutableListOf<AudioTrack>()

    override fun init(output: ExtractorOutput) {
        audioTracks.clear()
        super.init(object : ExtractorOutput by output {
            override fun track(id: Int, type: Int): TrackOutput {
                val target = output.track(id, type)
                return if (type == C.TRACK_TYPE_AUDIO) AudioTrack(id, target, output).also { audioTracks.add(it) }
                    else target
            }
        })
    }

    override fun seek(position: Long, timeUs: Long) {
        audioTracks.forEach { it.reset() }
        super.seek(position, timeUs)
    }

    private class AudioTrack(
        private val id: Int,
        private val target: TrackOutput,
        private val extractorOutput: ExtractorOutput,
    ) : ForwardingTrackOutput(target) {
        private var reader: Ac3Reader? = null
        private var needsTimestamp = true
        private val chunk = ByteArrayOutputStream()
        private val scratch = ByteArray(16 * 1024)

        override fun format(format: Format) {
            super.format(format)
            if (format.sampleMimeType != MimeTypes.AUDIO_AC3) {
                reader = null
                reset()
                return
            }
            reader = Ac3Reader(format.language, format.roleFlags, MimeTypes.VIDEO_AVI).apply {
                createTracks(object : ExtractorOutput by extractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput = object : ForwardingTrackOutput(target) {
                        override fun format(parsed: Format) {
                            // Keep AVI labels/selection metadata while using the actual AC3 header.
                            target.format(format.buildUpon().setSampleMimeType(parsed.sampleMimeType)
                                .setContainerMimeType(MimeTypes.VIDEO_AVI)
                                .setSampleRate(parsed.sampleRate).setChannelCount(parsed.channelCount)
                                .setAverageBitrate(parsed.averageBitrate).setPeakBitrate(parsed.peakBitrate)
                                .setMaxInputSize(maxOf(format.maxInputSize, 4096)).build())
                        }
                    }
                }, TrackIdGenerator(id, 1))
            }
            reset()
        }

        fun reset() {
            chunk.reset()
            needsTimestamp = true
            reader?.seek()
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
            sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, part: Int): Int {
            if (reader == null) return target.sampleData(input, length, allowEndOfInput, part)
            val count = input.read(scratch, 0, minOf(length, scratch.size))
            if (count == C.RESULT_END_OF_INPUT) {
                if (!allowEndOfInput) throw EOFException()
                return count
            }
            chunk.write(scratch, 0, count)
            return count
        }

        override fun sampleData(data: ParsableByteArray, length: Int) =
            sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

        override fun sampleData(data: ParsableByteArray, length: Int, part: Int) {
            if (reader == null) { target.sampleData(data, length, part); return }
            chunk.write(data.data, data.position, length)
            data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
            val ac3 = reader ?: run { target.sampleMetadata(timeUs, flags, size, offset, crypto); return }
            // AviExtractor emits each complete clear-text chunk with no trailing offset.
            check(offset == 0 && crypto == null && size == chunk.size())
            if (needsTimestamp) {
                ac3.packetStarted(timeUs, 0)
                needsTimestamp = false
            }
            // After the initial/seek anchor, advance by decoded sample counts. AVI's
            // averaged chunk timestamps cannot describe variable-sized audio chunks.
            ac3.consume(ParsableByteArray(chunk.toByteArray()))
            chunk.reset()
        }
    }
}
