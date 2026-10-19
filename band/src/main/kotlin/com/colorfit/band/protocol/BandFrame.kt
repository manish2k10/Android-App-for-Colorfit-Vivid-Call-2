package com.colorfit.band.protocol

/**
 * MOYOUNG framing (`o8/t1.b` and `i9/a.S2` in NoiseFit Track; see
 * docs/band-protocol.md):
 *
 * ```
 * FE EA <flag> <len> <cmd> <payload…>      len = payload + 5
 * ```
 *
 * `flag` is `0x10` when the packet size is 20 bytes (the default 23-byte MTU,
 * which is all this app uses) and `0x20 | (len >> 8)` otherwise. Frames longer
 * than one packet are written, and arrive, in 20-byte pieces.
 */
object BandFrame {

    private const val HEADER_0 = 0xFE.toByte()
    private const val HEADER_1 = 0xEA.toByte()
    private const val FLAG_20_BYTE_PACKETS = 0x10

    /** Bytes per write with the default MTU. */
    const val PACKET_SIZE = 20

    /** One complete frame from the band. */
    class Frame(val cmd: Int, val payload: ByteArray)

    fun build(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = payload.size + 5
        // The single length byte of the 0x10 form can't say more than this.
        require(len <= 0xFF) { "frame too long: $len" }
        return ByteArray(len).also {
            it[0] = HEADER_0
            it[1] = HEADER_1
            it[2] = FLAG_20_BYTE_PACKETS.toByte()
            it[3] = len.toByte()
            it[4] = cmd.toByte()
            payload.copyInto(it, 5)
        }
    }

    /** The frame split into the pieces that are written one by one. */
    fun packets(frame: ByteArray): List<ByteArray> =
        (frame.indices step PACKET_SIZE).map { frame.copyOfRange(it, minOf(it + PACKET_SIZE, frame.size)) }

    /**
     * Rebuilds frames from `FEE3` notifications. A notification starting with
     * `FE EA` opens a new frame whose length comes from its header; the rest
     * are appended until it is full.
     */
    class Reassembler {
        private var buffer: ByteArray? = null
        private var filled = 0

        /** Forget a half-received frame (e.g. on a new connection). */
        fun reset() {
            buffer = null
            filled = 0
        }

        fun feed(chunk: ByteArray): Frame? {
            if (chunk.size >= 4 && chunk[0] == HEADER_0 && chunk[1] == HEADER_1) {
                buffer = ByteArray(frameLength(chunk[2], chunk[3]))
                filled = 0
            }
            val buf = buffer ?: return null
            val n = minOf(chunk.size, buf.size - filled)
            chunk.copyInto(buf, filled, 0, n)
            filled += n
            if (filled < buf.size) return null
            buffer = null
            if (buf.size < 5) return null
            return Frame(buf[4].toInt() and 0xFF, buf.copyOfRange(5, buf.size))
        }

        /** `i9/a.c0`: one length byte in the 0x10 form, nine bits otherwise. */
        private fun frameLength(flag: Byte, low: Byte): Int {
            val lo = low.toInt() and 0xFF
            return if (flag.toInt() == FLAG_20_BYTE_PACKETS) lo else ((flag.toInt() and 0x01) shl 8) or lo
        }
    }
}
