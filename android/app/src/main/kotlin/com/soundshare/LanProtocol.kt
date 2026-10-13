package com.soundshare

/** Texto de emparejamiento y descubrimiento; los nombres pueden tener espacios. */
object LanProtocol {
    const val DISCOVERY_PORT = 47101
    const val MAX_DATAGRAM = 512
    data class Pairing(val pcId: String, val port: Int, val secret: String, val name: String)
    data class Reply(val pcId: String, val port: Int, val name: String)

    fun validHex(value: String, bytes: Int) = value.length == bytes * 2 &&
        value.all { it in '0'..'9' || it in 'a'..'f' }

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val result = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 255
            result[i * 2] = digits[b ushr 4]
            result[i * 2 + 1] = digits[b and 15]
        }
        return String(result)
    }

    fun bytes(hex: String): ByteArray {
        require(validHex(hex, hex.length / 2))
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun port(value: String): Int? = value.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
        ?.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun validName(value: String) = value.isNotBlank() && value.none { it.code < 32 || it.code == 127 }

    fun parsePairing(line: String): Pairing? {
        val p = line.split(' ', limit = 5)
        if (p.size != 5 || p[0] != "LAN_PAIRED" || !validHex(p[1], 16) ||
            !validHex(p[3], 32) || !validName(p[4])) return null
        return Pairing(p[1], port(p[2]) ?: return null, p[3], p[4])
    }

    fun discoveryRequest(phoneId: String): ByteArray {
        require(validHex(phoneId, 16))
        return "SOUNDSHARE? 1 $phoneId".toByteArray(Charsets.UTF_8)
    }

    fun parseReply(raw: ByteArray, length: Int = raw.size): Reply? {
        if (length !in 1..MAX_DATAGRAM || length > raw.size) return null
        val text = String(raw, 0, length, Charsets.UTF_8)
        // UTF-8 inválido no debe convertirse silenciosamente en un nombre válido.
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(raw.copyOf(length))) return null
        val p = text.split(' ', limit = 5)
        if (p.size != 5 || p[0] != "SOUNDSHARE" || p[1] != "1" ||
            !validHex(p[2], 16) || !validName(p[4])) return null
        return Reply(p[2], port(p[3]) ?: return null, p[4])
    }

    fun broadcast(address: ByteArray, prefix: Int): ByteArray? {
        if (address.size != 4 || prefix !in 1..30) return null
        return ByteArray(4) { i ->
            val bits = (prefix - i * 8).coerceIn(0, 8)
            (address[i].toInt() or (255 ushr bits)).toByte()
        }
    }
}
