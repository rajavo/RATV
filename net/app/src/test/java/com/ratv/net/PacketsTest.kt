package com.ratv.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketsTest {

    private fun ipv4(proto: Int, l4: ByteArray): ByteArray {
        val h = ByteArray(20)
        h[0] = 0x45
        h[9] = proto.toByte()
        byteArrayOf(10, 0, 0, 5).copyInto(h, 12)
        byteArrayOf(93, 184.toByte(), 216.toByte(), 34).copyInto(h, 16)
        return h + l4
    }

    /** TCP header from port 40000 to 443 with these flags. */
    private fun tcp(flags: Int) = ByteArray(20).also {
        it[0] = 0x9C.toByte()
        it[1] = 0x40
        it[2] = 0x01
        it[3] = 0xBB.toByte()
        it[12] = 0x50
        it[13] = flags.toByte()
    }

    @Test
    fun tcpSynOpensAConnection() {
        val f = Packets.parse(ipv4(6, tcp(0x02)), 40)!!
        assertEquals(Packets.TCP, f.protocol)
        assertEquals(40000, f.srcPort)
        assertEquals(443, f.dstPort)
        assertTrue(f.opens)
        assertArrayEquals(byteArrayOf(93, 184.toByte(), 216.toByte(), 34), f.dst)
        assertEquals("6|10.0.0.5.|40000|93.184.216.34.|443", f.key())
    }

    @Test
    fun laterTcpPacketsDoNotCountAgain() {
        assertFalse(Packets.parse(ipv4(6, tcp(0x10)), 40)!!.opens)
        assertFalse(Packets.parse(ipv4(6, tcp(0x12)), 40)!!.opens)
    }

    @Test
    fun udpOverIpv6() {
        val p = ByteArray(48)
        p[0] = 0x60
        p[6] = 17
        p[23] = 1
        p[39] = 2
        p[40] = 0x30
        p[41] = 0x39
        p[43] = 53
        val f = Packets.parse(p, 48)!!
        assertEquals(Packets.UDP, f.protocol)
        assertEquals(12345, f.srcPort)
        assertEquals(53, f.dstPort)
        assertTrue(f.opens)
        assertEquals(16, f.src.size)
    }

    @Test
    fun ignoresOtherPackets() {
        assertNull(Packets.parse(ipv4(1, ByteArray(8)), 28)) // ping
        assertNull(Packets.parse(ByteArray(10), 10))
        assertNull(Packets.parse(ipv4(6, ByteArray(2)), 22)) // cut short
        val fragment = ipv4(17, ByteArray(8)).also { it[7] = 10 }
        assertNull(Packets.parse(fragment, 28))
    }
}
