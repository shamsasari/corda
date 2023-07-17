package net.corda.nodeapi.internal.crypto

import net.corda.core.crypto.secureRandomBytes
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Encryption {
    const val AES_KEY_SIZE_BYTES = 16
    internal const val AES_IV_SIZE_BYTES = 12
    private const val AES_TAG_SIZE_BYTES = 16
    private const val AES_TAG_SIZE_BITS = AES_TAG_SIZE_BYTES * 8

    fun randomAesKey(): SecretKey {
        return SecretKeySpec(secureRandomBytes(AES_KEY_SIZE_BYTES), "AES")
    }

    /**
     * Encrypt the given [plaintext] with AES using the given [aesKey].
     *
     * An optional [authenticatedData] bytes can also be provided which will be authenticated alongside the ciphertext but not encrypted.
     * This may be metadata for example. The same authenticated data bytes must be provided to [aesDecrypt] to be able to decrypt the
     * ciphertext. Typically these bytes are serialised alongside the ciphertext. Since it's authenticated in the ciphertext, it cannot be
     * modified undetected.
     */
    fun aesEncrypt(aesKey: SecretKey, plaintext: ByteArray, authenticatedData: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = secureRandomBytes(AES_IV_SIZE_BYTES)  // Never use the same IV with the same key!
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(AES_TAG_SIZE_BITS, iv))
        val buffer = ByteBuffer.allocate(AES_IV_SIZE_BYTES + plaintext.size + AES_TAG_SIZE_BYTES)
        buffer.put(iv)
        if (authenticatedData != null) {
            cipher.updateAAD(authenticatedData)
        }
        cipher.doFinal(ByteBuffer.wrap(plaintext), buffer)
        return buffer.array()
    }

    fun aesEncrypt(aesKey: ByteArray, plaintext: ByteArray, authenticatedData: ByteArray? = null): ByteArray {
        return aesEncrypt(SecretKeySpec(aesKey, "AES"), plaintext, authenticatedData)
    }

    /**
     * Decrypt ciphertext that was encrypted with the same key using [aesEncrypt]. If additional authenticated data was used for the
     * encryption then it must also be provided.
     */
    fun aesDecrypt(aesKey: SecretKey, ciphertext: ByteArray, authenticatedData: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(AES_TAG_SIZE_BITS, ciphertext, 0, AES_IV_SIZE_BYTES))
        if (authenticatedData != null) {
            cipher.updateAAD(authenticatedData)
        }
        return cipher.doFinal(ciphertext, AES_IV_SIZE_BYTES, ciphertext.size - AES_IV_SIZE_BYTES)
    }

    fun aesDecrypt(aesKey: ByteArray, ciphertext: ByteArray, authenticatedData: ByteArray? = null): ByteArray {
        return aesDecrypt(SecretKeySpec(aesKey, "AES"), ciphertext, authenticatedData)
    }
}
