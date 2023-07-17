package net.corda.node.services

/**
 * A service for encrypting data. This abstraction does not mandate any security properties except the same service instance will be
 * able to decrypt ciphertext encrypted by it. Further security properties are defined by the implementations.
 */
interface EncryptionService {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray
}
