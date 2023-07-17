package net.corda.nodeapi.internal.crypto

import net.corda.core.crypto.secureRandomBytes
import net.corda.nodeapi.internal.crypto.Encryption.AES_KEY_SIZE_BYTES
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.Test
import java.security.GeneralSecurityException

class EncryptionTest {
    private val aesKey = secureRandomBytes(AES_KEY_SIZE_BYTES)
    private val plaintext = secureRandomBytes(257)  // Intentionally not a power of 2

    @Test(timeout = 300_000)
    fun `AES ciphertext can be decrypted using the same key`() {
        val ciphertext = Encryption.aesEncrypt(aesKey, plaintext)
        assertThat(String(ciphertext)).doesNotContain(String(plaintext))
        val decrypted = Encryption.aesDecrypt(aesKey, ciphertext)
        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test(timeout = 300_000)
    fun `AES ciphertext with authenticated data can be decrypted using the same key`() {
        val ciphertext = Encryption.aesEncrypt(aesKey, plaintext, "Extra public data".toByteArray())
        assertThat(String(ciphertext)).doesNotContain(String(plaintext))
        val decrypted = Encryption.aesDecrypt(aesKey, ciphertext, "Extra public data".toByteArray())
        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test(timeout = 300_000)
    fun `AES ciphertext cannot be decrypted with different authenticated data`() {
        val ciphertext = Encryption.aesEncrypt(aesKey, plaintext, "Extra public data".toByteArray())
        assertThat(String(ciphertext)).doesNotContain(String(plaintext))
        assertThatExceptionOfType(GeneralSecurityException::class.java).isThrownBy {
            Encryption.aesDecrypt(aesKey, ciphertext, "Different public data".toByteArray())
        }
    }

    @Test(timeout = 300_000)
    fun `AES ciphertext cannot be decrypted with different key`() {
        val ciphertext = Encryption.aesEncrypt(aesKey, plaintext)
        for (index in aesKey.indices) {
            aesKey[index]--
            assertThatExceptionOfType(GeneralSecurityException::class.java).isThrownBy {
                Encryption.aesDecrypt(aesKey, ciphertext)
            }
            aesKey[index]++
        }
    }

    @Test(timeout = 300_000)
    fun `corrupted AES ciphertext cannot be decrypted`() {
        val ciphertext = Encryption.aesEncrypt(aesKey, plaintext)
        for (index in ciphertext.indices) {
            ciphertext[index]--
            assertThatExceptionOfType(GeneralSecurityException::class.java).isThrownBy {
                Encryption.aesDecrypt(aesKey, ciphertext)
            }
            ciphertext[index]++
        }
    }

    @Test(timeout = 300_000)
    fun `encrypting same plainttext twice with same key does not produce same ciphertext`() {
        val first = Encryption.aesEncrypt(aesKey, plaintext)
        val second = Encryption.aesEncrypt(aesKey, plaintext)
        // The IV should be different
        assertThat(first.take(Encryption.AES_IV_SIZE_BYTES)).isNotEqualTo(second.take(Encryption.AES_IV_SIZE_BYTES))
        // Which should cause the encrypted bytes to be different as well
        assertThat(first.drop(Encryption.AES_IV_SIZE_BYTES)).isNotEqualTo(second.drop(Encryption.AES_IV_SIZE_BYTES))
    }
}
