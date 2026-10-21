package com.ratv.net

/** The little RA Net needs from a packet a blocked app sent: where it goes and whether it opens a connection. */
object Packets {
    const val TCP = 6
    const val UDP = 17

    class Flow(
        val protocol: Int,
        val src: ByteArray,
        val srcPort: Int,
        val dst: ByteArray,
        val dstPort: Int,
        /** A new TCP connection (SYN) or a UDP message: one attempt to use the internet. */
        val opens: Boolean
    ) {
        fun key(): String = buildString {
            append(protocol).append('|')
            src.forEach { append(it.toInt() and 0xFF).append('.') }
            append(srcPort).append('|')
            dst.forEach { append(it.toInt() and 0xFF).append('.') }
            append(dstPort)
        }
    }

    fun parse(b: ByteArray, len: Int): Flow? {
        if (len < 1) return null
        val proto: Int
        val src: ByteArray
        val dst: ByteArray
        val l4: Int
        when ((b[0].toInt() ushr 4) and 0xF) {
            4 -> {
                if (len < 20) return null
                l4 = (b[0].toInt() and 0xF) * 4
                proto = b[9].toInt() and 0xFF
                // Only the first fragment carries the ports.
                val fragmentOffset = ((b[6].toInt() and 0x1F) shl 8) or (b[7].toInt() and 0xFF)
                if (fragmentOffset != 0) return null
                src = b.copyOfRange(12, 16)
                dst = b.copyOfRange(16, 20)
            }
            6 -> {
                if (len < 40) return null
                l4 = 40
                proto = b[6].toInt() and 0xFF
                src = b.copyOfRange(8, 24)
                dst = b.copyOfRange(24, 40)
            }
            else -> return null
        }
        if (proto != TCP && proto != UDP) return null
        if (len < l4 + 4) return null
        val opens = if (proto == UDP) true else len >= l4 + 14 && (b[l4 + 13].toInt() and 0x12) == 0x02
        return Flow(proto, src, u16(b, l4), dst, u16(b, l4 + 2), opens)
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
