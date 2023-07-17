package net.corda.testing.node.internal

import net.corda.node.services.EncryptionService
import net.corda.nodeapi.internal.crypto.Encryption
import net.corda.nodeapi.internal.crypto.Encryption.randomAesKey
import javax.crypto.SecretKey

class MockEncryptionService(private val aesKey: SecretKey = randomAesKey()) : EncryptionService {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        return Encryption.aesEncrypt(aesKey, plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        return Encryption.aesDecrypt(aesKey, ciphertext)
    }
}
