package cc.cicare.sdkcall.libs

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AES256Decryptor {

    fun decrypt(cipherText: String, encryptionKey: String): String {
        try {
            // Pisahkan IV dan encrypted data seperti "ivHex:encryptedHex"
            val parts = cipherText.split(":")
            if (parts.size != 2) {
                throw IllegalArgumentException("Invalid cipher text format, expected iv:encrypted")
            }

            val ivHex = parts[0]
            val encryptedHex = parts[1]

            val ivBytes = hexToBytes(ivHex)
            val encryptedBytes = hexToBytes(encryptedHex)

            // Key harus 32 byte untuk AES-256
            val keyBytes = encryptionKey.toByteArray(Charsets.UTF_8)
            require(keyBytes.size == 32) {"0123456789abcdef0123456789abcdef" }

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException(" SDK CALL C Decryption failed: ${e.message}")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            val byte = hex.substring(i, i + 2).toInt(16)
            result[i / 2] = byte.toByte()
        }
        return result
    }
}
