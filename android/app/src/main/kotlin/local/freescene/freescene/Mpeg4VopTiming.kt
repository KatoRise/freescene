package local.freescene.freescene

import androidx.media3.common.util.ParsableBitArray

/** MPEG-4 Part 2 VOL/VOP timing (ISO/IEC 14496-2 sections 6.2.3 and 6.2.5).
 * AVI chunk order is decode order, not presentation order when B-VOPs are present.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class Mpeg4VopTiming {
    private data class Clock(
        var resolution: Int = 0,
        var seconds: Long = 0,
        var previousReferenceSeconds: Long = 0,
        var anchorUs: Long? = null,
    )
    private var clock = Clock()
    private var disabled = false

    fun reset() {
        clock = Clock(resolution = clock.resolution)
        disabled = false
    }

    fun disable() { disabled = true }

    fun configure(data: ByteArray) { timestamp(data, 0) }

    fun timestamp(data: ByteArray, aviTimeUs: Long): Long {
        if (disabled) return aviTimeUs
        val next = clock.copy()
        var presentationUs: Long? = null
        var vops = 0
        try {
            var start = startCode(data, 0)
            while (start >= 0) {
                val end = startCode(data, start + 4).let { if (it < 0) data.size else it }
                val code = data[start + 3].toInt() and 0xff
                val bits = Bits(data, start + 4, end)
                when (code) {
                    in 0x20..0x2f -> next.resolution = resolution(bits)
                    0xb3 -> {
                        val hours = bits.read(5)
                        val minutes = bits.read(6)
                        bits.marker()
                        val seconds = bits.read(6)
                        require(minutes < 60 && seconds < 60)
                        bits.skip(2) // closed_gov, broken_link
                        next.seconds = (hours * 3600 + minutes * 60 + seconds).toLong()
                    }
                    0xb6 -> {
                        // Packed DivX contains multiple VOPs in one chunk. It needs a
                        // separate packetizer; leave that layout unchanged here.
                        require(++vops == 1)
                        if (next.resolution != 0) {
                            val type = bits.read(2)
                            var seconds = 0L
                            while (bits.read(1) != 0) seconds++
                            bits.marker()
                            val increment = bits.read(incrementBits(next.resolution))
                            require(increment < next.resolution)
                            bits.marker()
                            bits.skip(1) // vop_coded; uncoded VOPs still advance the clock.
                            val base = if (type == 2) {
                                next.previousReferenceSeconds + seconds
                            } else {
                                next.previousReferenceSeconds = next.seconds
                                next.seconds += seconds
                                next.seconds
                            }
                            val timeUs = base * 1_000_000 + increment * 1_000_000L / next.resolution
                            if (next.anchorUs == null) next.anchorUs = aviTimeUs - timeUs
                            presentationUs = timeUs + next.anchorUs!!
                        }
                    }
                }
                start = if (end < data.size) end else -1
            }
            clock = next
            return presentationUs ?: aviTimeUs
        } catch (_: IllegalArgumentException) {
            // Invalid/unsupported headers must not break an otherwise playable file.
            // Stop using the partial clock until seek, rather than extrapolating it.
            disabled = true
            return aviTimeUs
        }
    }

    private fun resolution(bits: Bits): Int {
        bits.skip(1) // random_accessible_vol
        val objectType = bits.read(8)
        require(objectType != 14 && objectType != 15) // Studio VOL has different syntax.
        if (bits.read(1) != 0) bits.skip(7) // layer version and priority
        if (bits.read(4) == 15) bits.skip(16) // extended pixel aspect ratio
        if (bits.read(1) != 0) { // vol_control_parameters
            bits.skip(3) // chroma_format, low_delay
            if (bits.read(1) != 0) { // vbv_parameters
                bits.skip(15); bits.marker()
                bits.skip(15); bits.marker()
                bits.skip(15); bits.marker()
                bits.skip(14); bits.marker()
                bits.skip(15); bits.marker()
            }
        }
        require(bits.read(2) == 0) // rectangular video object layer
        bits.marker()
        val resolution = bits.read(16)
        require(resolution > 0)
        bits.marker()
        if (bits.read(1) != 0) bits.skip(incrementBits(resolution)) // fixed_vop_time_increment
        return resolution
    }

    private fun incrementBits(resolution: Int) = maxOf(1, 32 - Integer.numberOfLeadingZeros(resolution - 1))

    private fun startCode(data: ByteArray, from: Int): Int {
        for (i in from until data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) return i
        }
        return -1
    }

    private class Bits(data: ByteArray, start: Int, end: Int) {
        private val bits = ParsableBitArray(data, end).apply { position = start * 8 }
        fun read(count: Int): Int { require(bits.bitsLeft() >= count); return bits.readBits(count) }
        fun skip(count: Int) { require(bits.bitsLeft() >= count); bits.skipBits(count) }
        fun marker() { require(read(1) == 1) }
    }
}
