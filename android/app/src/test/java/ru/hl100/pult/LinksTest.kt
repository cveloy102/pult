package ru.hl100.pult

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    private val pin = "A".repeat(64)

    @Test fun parsesAgentQrLink() {
        // Same string as the agent test in Pult.Tests (PairingLink.Create).
        val link = PairLink.parse("pult://pair?v=1&a=192.168.1.5%3A47591%2C10.0.0.2%3A47591&f=$pin&c=123456&n=%D0%9C%D0%BE%D0%B9%20%D0%9F%D0%9A%20%26%20%D0%B8%D0%B3%D1%80%D1%8B")!!
        assertEquals(listOf("192.168.1.5:47591", "10.0.0.2:47591"), link.addresses)
        assertEquals(pin, link.fingerprint)
        assertEquals("123456", link.code)
        assertEquals("Мой ПК & игры", link.name)
    }

    @Test fun rejectsForeignOrIncompleteLinks() {
        assertNull(PairLink.parse("https://example.com/pair?v=1&a=1.2.3.4&f=$pin&c=123456"))
        assertNull(PairLink.parse("pult://pair?v=2&a=192.168.1.5:47591&f=$pin&c=123456"))
        assertNull(PairLink.parse("pult://pair?v=1&a=192.168.1.5:47591&f=ABC&c=123456"))
        assertNull(PairLink.parse("pult://pair?v=1&a=192.168.1.5:47591&f=$pin&c=12345"))
        assertNull(PairLink.parse("pult://pair?v=1&a=&f=$pin&c=123456"))
        assertNull(PairLink.parse("просто текст"))
    }

    @Test fun buildsMagicPacket() {
        val mac = Wake.parseMac("00:1A:2B:3C:4D:5E")!!
        val packet = Wake.packet(mac)
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        for (i in 0 until 16) assertArrayEquals(mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
    }

    @Test fun validatesMacAndBroadcast() {
        assertNull(Wake.parseMac("00:00:00:00:00:00"))
        assertNull(Wake.parseMac("00:1A:2B:3C:4D"))
        assertNull(Wake.parseMac("00:1A:2B:3C:4D:ZZ"))
        assertTrue(Wake.isLocalBroadcast("192.168.1.255"))
        assertTrue(Wake.isLocalBroadcast("10.0.255.255"))
        assertFalse(Wake.isLocalBroadcast("8.8.8.255"))
        assertFalse(Wake.isLocalBroadcast("example.com"))
    }

    @Test fun formatsLongTimers() {
        assertEquals("05:07", formatRemaining(307))
        assertEquals("1:29:37", formatRemaining(5377))
        assertEquals("24:00:00", formatRemaining(86400))
    }
}
