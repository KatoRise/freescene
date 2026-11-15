package local.freescene.freescene

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.extractor.*
import androidx.media3.extractor.avi.AviExtractor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AviAc3ExtractorTest {
    // Generated silence, no third-party media: ffmpeg -f lavfi -i anullsrc=r=48000:cl=stereo
    // -frames:a 1 -c:a ac3 -b:a 192k; retain the first 768-byte syncframe (encoder flush adds another).
    private val frame = javaClass.getResourceAsStream("/audio/silence-48k-stereo.ac3")!!.readBytes()
    private data class Sample(val time: Long, val bytes: ByteArray)
    private class Output : ExtractorOutput, TrackOutput {
        val samples = mutableListOf<Sample>()
        private val data = ByteArrayOutputStream()
        lateinit var format: Format
        lateinit var seekMap: SeekMap
        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, part: Int): Int {
            val bytes = ByteArray(minOf(length, 113)) // Exercise partial reads, including headers.
            val count = input.read(bytes, 0, bytes.size)
            if (count > 0) data.write(bytes, 0, count)
            return count
        }
        override fun sampleData(input: ParsableByteArray, length: Int, part: Int) {
            val bytes = ByteArray(length); input.readBytes(bytes, 0, length); data.write(bytes)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
            val bytes = data.toByteArray(); val end = bytes.size - offset
            samples.add(Sample(timeUs, bytes.copyOfRange(end - size, end)))
        }
    }
    private fun extract(extractor: Extractor, bytes: ByteArray, output: Output = Output(), start: Long = 0): Output {
        if (start == 0L) extractor.init(output)
        var position = start
        val seek = PositionHolder()
        while (true) {
            val source = ByteArrayDataSource(bytes)
            source.open(DataSpec.Builder().setUri(Uri.parse("memory:///fixture.avi")).setPosition(position).build())
            val input = DefaultExtractorInput(source, position, bytes.size.toLong())
            var result = Extractor.RESULT_CONTINUE
            while (result == Extractor.RESULT_CONTINUE) result = extractor.read(input, seek)
            source.close()
            if (result == Extractor.RESULT_END_OF_INPUT) return output
            position = seek.position
        }
    }
    private fun chunk(id: String, bytes: ByteArray) = id.toByteArray() + ints(bytes.size) + bytes + ByteArray(bytes.size % 2)
    private fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        values.forEach { putInt(it) }
    }.array()
    private fun avi(chunks: List<ByteArray>, tag: Int = 0x2000, indexed: Boolean = true): ByteArray {
        val size = chunks.sumOf { it.size }
        val main = ints(40000, 0, 0, if (indexed) 0x10 else 0, size / 960, 0, 1, chunks.maxOf { it.size }, 0, 0, 0, 0, 0, 0)
        val stream = "auds".toByteArray() + ints(0, 0, 0, 0, 1, 24000, 0, size, chunks.maxOf { it.size }, -1, 1, 0, 0)
        val format = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(tag.toShort()); putShort(2); putInt(48000); putInt(24000)
            putShort(1); putShort(16); putShort(0)
        }.array()
        val headers = chunk("LIST", "hdrl".toByteArray() + chunk("avih", main) +
            chunk("LIST", "strl".toByteArray() + chunk("strh", stream) + chunk("strf", format) + chunk("strn", "Test AC3\u0000".toByteArray())))
        var offset = 4
        val index = ByteArrayOutputStream()
        val media = ByteArrayOutputStream()
        chunks.forEach {
            index.write("00wb".toByteArray() + ints(0x10, offset, it.size))
            val entry = chunk("00wb", it); media.write(entry); offset += entry.size
        }
        return chunk("RIFF", "AVI ".toByteArray() + headers + chunk("LIST", "movi".toByteArray() + media.toByteArray()) + chunk("idx1", index.toByteArray()))
    }
    @Suppress("DEPRECATION")
    private fun sinkErrors(output: Output): List<Exception> {
        org.robolectric.shadows.ShadowAudioTrack.addAllowedNonPcmEncoding(C.ENCODING_AC3)
        val errors = mutableListOf<Exception>()
        val sink = DefaultAudioSink.Builder().setAudioCapabilities(AudioCapabilities(intArrayOf(C.ENCODING_AC3, C.ENCODING_PCM_16BIT), 2)).build()
        sink.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() {}
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {}
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {}
            override fun onAudioSinkError(error: Exception) { errors.add(error) }
        })
        sink.configure(output.format, 65536, null)
        sink.play()
        try {
            output.samples.forEach { sample ->
                val buffer = ByteBuffer.allocateDirect(sample.bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(sample.bytes); flip() }
                assertTrue("Audio output rejected sample ${sample.time}: $errors", sink.handleBuffer(buffer, sample.time, 1))
            }
        } finally { sink.release(); org.robolectric.shadows.ShadowAudioTrack.clearAllowedNonPcmEncodings() }
        return errors
    }
    @Test fun groupedAc3FramesReproduceTheTelevisionsAudioClockDiscontinuity() {
        val output = extract(AviExtractor(), avi(List(60) { if (it % 3 == 0) frame + frame else frame }))
        assertEquals(60, output.samples.size)
        assertTrue(output.samples.any { it.bytes.size == frame.size * 2 })
        val errors = sinkErrors(output)
        assertTrue("$errors", errors.any { it is AudioSink.UnexpectedDiscontinuityException })
        println("Unmodified Media3: ${errors.size} audio sink discontinuities in ${output.samples.size} AVI chunks")
    }

    private fun fixed(): Extractor = PlayerExtractors().createExtractors(Uri.parse("memory:///fixture.avi"), emptyMap())
        .single { it.underlyingImplementation is AviExtractor }

    @Test fun groupedFramesReachTheRealAudioSinkWithContinuousTimeAndNoLostBytes() {
        val bytes = avi(List(60) { if (it % 3 == 0) frame + frame else frame })
        val output = extract(fixed(), bytes)
        assertEquals(80, output.samples.size)
        output.samples.forEachIndexed { i, sample ->
            assertEquals(i * 32000L, sample.time)
            assertArrayEquals(frame, sample.bytes)
        }
        assertEquals(extract(AviExtractor(), bytes).format.label, output.format.label)
        assertEquals(48000, output.format.sampleRate)
        assertEquals(2, output.format.channelCount)
        assertTrue(sinkErrors(output).isEmpty())
        println("Framed AC3: 0 audio sink discontinuities, all 80 frames preserved")
    }

    @Test fun syncWordsHeadersAndFramesCanCrossAviChunkBoundaries() {
        val bytes = frame + frame + frame
        val split = listOf(bytes.copyOfRange(0, 1), bytes.copyOfRange(1, 63),
            bytes.copyOfRange(63, 800), bytes.copyOfRange(800, 1291), bytes.copyOfRange(1291, bytes.size))
        val output = extract(fixed(), avi(split))
        assertEquals(listOf(0L, 32000L, 64000L), output.samples.map { it.time })
        output.samples.forEach { assertArrayEquals(frame, it.bytes) }
        assertTrue(sinkErrors(output).isEmpty())
    }

    @Test fun seekingResetsFramingAndAnchorsToTheSelectedAviPosition() {
        val extractor = fixed()
        val bytes = avi(List(60) { frame + frame })
        val output = extract(extractor, bytes)
        for (time in listOf(1_280_000L, 640_000L)) {
            val point = output.seekMap.getSeekPoints(time).first
            output.samples.clear()
            extractor.seek(point.position, point.timeUs)
            extract(extractor, bytes, output, point.position)
            assertTrue(output.samples.isNotEmpty())
            output.samples.forEachIndexed { i, sample ->
                assertEquals(point.timeUs + i * 32000L, sample.time)
                assertArrayEquals(frame, sample.bytes)
            }
            assertTrue(sinkErrors(output).isEmpty())
        }
    }

    @Test fun otherAviAudioIsForwardedWithoutChangingPacketsOrTimestamps() {
        val bytes = avi(List(60) { ByteArray(if (it % 3 == 0) 1536 else 768) { it.toByte() } }, tag = 1)
        val original = extract(AviExtractor(), bytes)
        val output = extract(fixed(), bytes)
        assertEquals(original.format, output.format)
        assertEquals(original.samples.map { it.time }, output.samples.map { it.time })
        original.samples.zip(output.samples).forEach { (before, after) -> assertArrayEquals(before.bytes, after.bytes) }
    }

    @Test fun factoryPreservesSniffingOrderAndOtherContainers() {
        val original = DefaultExtractorsFactory().createExtractors()
        val wrapped = PlayerExtractors().createExtractors()
        assertEquals(original.map { it.underlyingImplementation.javaClass }, wrapped.map { it.underlyingImplementation.javaClass })
        assertEquals(1, wrapped.count { it is AviAc3Extractor })
        original.zip(wrapped).filter { it.first.underlyingImplementation !is AviExtractor }.forEach { (before, after) ->
            assertEquals(before.javaClass, after.javaClass)
        }
    }

    @Test fun ordinaryOneFrameChunksKeepTheirTimingAndData() {
        val bytes = avi(List(60) { frame })
        val original = extract(AviExtractor(), bytes)
        val output = extract(fixed(), bytes)
        assertEquals(original.samples.map { it.time }, output.samples.map { it.time })
        original.samples.zip(output.samples).forEach { (before, after) -> assertArrayEquals(before.bytes, after.bytes) }
        assertTrue(sinkErrors(output).isEmpty())
    }

    @Test fun byteCountAudioWithoutAnIndexStillUsesAc3FrameTiming() {
        val output = extract(fixed(), avi(List(60) { frame + frame }, indexed = false))
        assertFalse(output.seekMap.isSeekable)
        assertEquals(120, output.samples.size)
        output.samples.forEachIndexed { i, sample -> assertEquals(i * 32000L, sample.time) }
        assertTrue(sinkErrors(output).isEmpty())
    }
}
