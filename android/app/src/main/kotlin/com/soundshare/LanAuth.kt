package com.soundshare

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Autenticación mutua antes del hello: no enviamos el rol hasta verificar a la PC. */
object LanAuth {
    private val magic = byteArrayOf(0x53, 0x53, 0x4c, 0x4e)
    private val random = SecureRandom()

    fun randomBytes(size: Int) = ByteArray(size).also { random.nextBytes(it) }

    fun request(phoneId: ByteArray, nonce: ByteArray): ByteArray {
        require(phoneId.size == 16 && nonce.size == 16)
        return ByteArray(40).also {
            System.arraycopy(magic, 0, it, 0, 4)
            it[4] = 1
            System.arraycopy(phoneId, 0, it, 8, 16)
            System.arraycopy(nonce, 0, it, 24, 16)
        }
    }

    private fun proof(secret: ByteArray, tag: String, phoneId: ByteArray, first: ByteArray, second: ByteArray): ByteArray {
        require(secret.size == 32 && phoneId.size == 16 && first.size == 16 && second.size == 16)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(tag.toByteArray(Charsets.US_ASCII))
        mac.update(phoneId)
        mac.update(first)
        return mac.doFinal(second)
    }

    fun pcProof(secret: ByteArray, phoneId: ByteArray, phoneNonce: ByteArray, pcNonce: ByteArray) =
        proof(secret, "SSLN-PC", phoneId, phoneNonce, pcNonce)

    fun phoneProof(secret: ByteArray, phoneId: ByteArray, phoneNonce: ByteArray, pcNonce: ByteArray) =
        proof(secret, "SSLN-PHONE", phoneId, pcNonce, phoneNonce)

    fun authenticate(input: InputStream, output: OutputStream, phoneId: ByteArray, secret: ByteArray,
                     phoneNonce: ByteArray = randomBytes(16)) {
        output.write(request(phoneId, phoneNonce))
        output.flush()
        val reply = ByteArray(56)
        DataInputStream(input).readFully(reply)
        for (i in magic.indices) if (reply[i] != magic[i]) throw IOException("preámbulo WiFi inválido")
        if (reply[4] != 1.toByte() || reply[6] != 0.toByte() || reply[7] != 0.toByte())
            throw IOException("versión WiFi no soportada")
        if (reply[5] != 0.toByte()) throw IOException("la PC rechazó el emparejamiento WiFi")
        val pcNonce = reply.copyOfRange(8, 24)
        if (!MessageDigest.isEqual(pcProof(secret, phoneId, phoneNonce, pcNonce), reply.copyOfRange(24, 56)))
            throw IOException("la PC no pudo autenticar el enlace WiFi")
        output.write(phoneProof(secret, phoneId, phoneNonce, pcNonce))
        output.flush()
    }
}
