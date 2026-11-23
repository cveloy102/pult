package ru.hl100.pult

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder

/** Pairing data from the QR code on the PC: pult://pair?v=1&a=ip:port,…&f=SHA256&c=CODE&n=NAME */
data class PairLink(val addresses: List<String>, val fingerprint: String, val code: String, val name: String) {
    companion object {
        fun parse(text: String): PairLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "pult" || uri.host != "pair") return null
            val query = uri.rawQuery ?: return null
            val values = query.split('&').mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index) to URLDecoder.decode(part.substring(index + 1), "UTF-8")
            }.toMap()
            if (values["v"] != "1") return null
            val fingerprint = values["f"]?.uppercase()?.takeIf { it.matches(Regex("[A-F0-9]{64}")) } ?: return null
            val code = values["c"]?.takeIf { it.matches(Regex("[0-9]{6}")) } ?: return null
            val addresses = values["a"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(8)
            if (addresses.isEmpty()) return null
            return PairLink(addresses, fingerprint, code, values["n"].orEmpty().ifBlank { "Компьютер" }.take(80))
        }
    }
}

/** Last known Wake-on-LAN address of the paired PC, reported by the agent while it was on. */
data class WakeTarget(val mac: String, val broadcast: String)

object Wake {
    fun parseMac(mac: String): ByteArray? {
        val parts = mac.split(':', '-')
        if (parts.size != 6) return null
        val bytes = parts.map { part -> part.takeIf { it.length == 2 }?.toIntOrNull(16)?.toByte() ?: return null }.toByteArray()
        return bytes.takeIf { value -> value.any { it != 0.toByte() } }
    }

    /** Magic packet: 6 × 0xFF followed by the MAC address 16 times. */
    fun packet(mac: ByteArray): ByteArray = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * mac.size) { mac[it % mac.size] }

    /** Only local IPv4 broadcast literals: the phone never sends wake packets to the internet. */
    fun isLocalBroadcast(address: String): Boolean {
        val parts = address.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1] in 16..31
    }

    /** Sends the packet several times to the subnet broadcast and 255.255.255.255, ports 9 and 7. Returns datagrams sent. */
    fun send(targets: List<WakeTarget>): Int {
        var sent = 0
        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(3) {
                for (target in targets) {
                    val mac = parseMac(target.mac) ?: continue
                    val data = packet(mac)
                    val hosts = listOfNotNull(target.broadcast.takeIf(::isLocalBroadcast), "255.255.255.255")
                    for (host in hosts) for (port in intArrayOf(9, 7)) {
                        try { socket.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port)); sent++ } catch (_: Exception) { }
                    }
                }
                Thread.sleep(150)
            }
        }
        return sent
    }
}
