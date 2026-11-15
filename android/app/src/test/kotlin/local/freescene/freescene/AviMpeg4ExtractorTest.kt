package local.freescene.freescene

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.*
import androidx.media3.extractor.avi.AviExtractor
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AviMpeg4ExtractorTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/video/$name")!!.readBytes()
    private val fixture = resource("mpeg4-bframes.avi")
    private val expected = JSONArray(String(resource("mpeg4-bframes.json")))
    private data class Sample(val time: Long, val bytes: ByteArray)
    private class Output : ExtractorOutput, TrackOutput {
        val samples = mutableListOf<Sample>()
        private val bytes = ByteArrayOutputStream()
        lateinit var seekMap: SeekMap
        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
        override fun format(format: Format) {}
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, part: Int): Int {
            val data = ByteArray(minOf(113, length))
            val n = input.read(data, 0, data.size)
            if (n > 0) bytes.write(data, 0, n)
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, part: Int) {
            bytes.write(data.data, data.position, length); data.skipBytes(length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) {
            val data = bytes.toByteArray()
            samples.add(Sample(timeUs, data.copyOfRange(data.size - offset - size, data.size - offset)))
            bytes.reset()
        }
    }
    private fun extract(extractor: Extractor, output: Output = Output(), start: Long = 0): Output {
        if (start == 0L) extractor.init(output)
        var position = start
        val seek = PositionHolder()
        while (true) {
            val source = ByteArrayDataSource(fixture)
            source.open(DataSpec.Builder().setUri(Uri.parse("memory:///fixture.avi")).setPosition(position).build())
            val input = DefaultExtractorInput(source, position, fixture.size.toLong())
            var result = Extractor.RESULT_CONTINUE
            while (result == Extractor.RESULT_CONTINUE) result = extractor.read(input, seek)
            source.close()
            if (result == Extractor.RESULT_END_OF_INPUT) return output
            position = seek.position
        }
    }
    private fun fixed() = PlayerExtractors().createExtractors().single { it.underlyingImplementation is AviExtractor }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun referenceTime(index: Int) = expected.getJSONObject(index).getLong("timeUs")
    private fun displayOrder() = (0 until expected.length()).sortedBy { referenceTime(it) }

    @Test fun originalAviTimestampsRunBackwardsAfterActualMpeg4Reordering() {
        val samples = extract(AviExtractor()).samples
        assertEquals(expected.length(), samples.size)
        val rendered = displayOrder().map { samples[it].time }
        val reversals = rendered.zipWithNext().count { (a, b) -> b <= a }
        assertTrue("Expected B-frame timestamp reversals, got $reversals", reversals >= 20)
        println("Unmodified AVI: $reversals timestamp reversals in MPEG-4 presentation order")
    }

    @Test fun fixedTimestampsMatchIndependentDecoderOrderAndPreserveAllCompressedBytes() {
        val samples = extract(fixed()).samples
        assertEquals(expected.length(), samples.size)
        samples.forEachIndexed { i, sample ->
            assertEquals("packet $i", referenceTime(i), sample.time)
            assertEquals(expected.getJSONObject(i).getString("sha256"), hash(sample.bytes))
        }
        val rendered = displayOrder().map { samples[it].time }
        assertTrue(rendered.zipWithNext().all { (a, b) -> b > a })
        println("Fixed AVI: 0 timestamp reversals, 60 compressed frames preserved")
    }

    @Test fun seeksResetClockAndKeepRelativePresentationTimes() {
        val extractor = fixed()
        val original = extract(AviExtractor()).samples
        val output = extract(extractor)
        for (timeUs in listOf(1_600_000L, 500_000L, 0L)) {
            val point = output.seekMap.getSeekPoints(timeUs).first
            output.samples.clear()
            extractor.seek(point.position, point.timeUs)
            extract(extractor, output, point.position)
            assertTrue(output.samples.isNotEmpty())
            val firstHash = hash(output.samples.first().bytes)
            val first = (0 until expected.length()).first { expected.getJSONObject(it).getString("sha256") == firstHash }
            // Media3's SeekPoint rounds a frame duration before multiplying, while
            // sampleMetadata rounds only after multiplying. Preserve the sample anchor.
            val offset = original[first].time - referenceTime(first)
            output.samples.forEachIndexed { i, sample ->
                assertEquals("seek=$timeUs packet=$i", referenceTime(first + i) + offset, sample.time)
            }
        }
    }

    private fun vol(resolution: Int = 1000, vbv: Boolean = false, objectType: Int = 1): ByteArray = start(0x20) + bits(buildString {
        append("0"); append(objectType.toString(2).padStart(8, '0')); append("0"); append("0001")
        if (vbv) {
            append("1"); append("010"); append("1")
            append("0".repeat(15)); append("1"); append("0".repeat(15)); append("1")
            append("0".repeat(15)); append("1"); append("0".repeat(14)); append("1")
            append("0".repeat(15)); append("1")
        } else append("0")
        append("00"); append("1"); append(resolution.toString(2).padStart(16, '0')); append("10")
    })
    private fun vop(type: Int, increment: Int, seconds: Int = 0, coded: Boolean = true): ByteArray = start(0xb6) +
        bits(type.toString(2).padStart(2, '0') + "1".repeat(seconds) + "01" + increment.toString(2).padStart(10, '0') + "1" + if (coded) "1" else "0")
    private fun start(code: Int) = byteArrayOf(0, 0, 1, code.toByte())
    private fun bits(value: String): ByteArray = value.padEnd((value.length + 7) / 8 * 8, '0').chunked(8).map { it.toInt(2).toByte() }.toByteArray()

    @Test fun bFramesUsePreviousReferenceSecondAcrossTheModuloBoundary() {
        val timing = Mpeg4VopTiming().apply { configure(vol(vbv = true)) }
        assertEquals(0L, timing.timestamp(vop(0, 920), 0))
        assertEquals(120_000L, timing.timestamp(vop(1, 40, seconds = 1), 40_000))
        assertEquals(40_000L, timing.timestamp(vop(2, 960), 80_000))
        assertEquals(80_000L, timing.timestamp(vop(2, 0, seconds = 1), 120_000))
        assertEquals(160_000L, timing.timestamp(vop(1, 80), 160_000))
    }

    @Test fun streamsWithoutBFramesKeepTheirTimestamps() {
        val timing = Mpeg4VopTiming().apply { configure(vol()) }
        for (i in 0..24) assertEquals(i * 40_000L, timing.timestamp(vop(if (i == 0) 0 else 1, i * 40), i * 40_000L))
    }

    @Test fun missingInvalidOrPackedHeadersFallBackWithoutInventingTimes() {
        val missing = Mpeg4VopTiming()
        assertEquals(123L, missing.timestamp(vop(1, 80), 123))
        for (bad in listOf(vol(0), vol(objectType = 14), vol(objectType = 15), start(0x20) + byteArrayOf(0), vop(1, 80) + vop(2, 40))) {
            val timing = Mpeg4VopTiming().apply { configure(vol()); timestamp(vop(0, 0), 0) }
            assertEquals(111L, timing.timestamp(bad, 111))
            assertEquals(222L, timing.timestamp(vop(1, 160), 222))
            timing.reset()
            assertEquals(333L, timing.timestamp(vop(0, 0), 333))
            assertEquals(80_333L, timing.timestamp(vop(1, 80), 444))
        }
    }

    @Test fun truncatedHeadersNeverCrash() {
        val header = vol() + vop(0, 0)
        for (length in 0 until header.size) Mpeg4VopTiming().timestamp(header.copyOf(length), 123)
    }

    @Test fun uncodedVopsStillAdvanceTheReferenceClock() {
        val timing = Mpeg4VopTiming().apply { configure(vol()) }
        timing.timestamp(vop(0, 960), 0)
        assertEquals(40_000L, timing.timestamp(vop(1, 0, seconds = 1, coded = false), 40_000))
        assertEquals(80_000L, timing.timestamp(vop(1, 40), 80_000))
    }
}
