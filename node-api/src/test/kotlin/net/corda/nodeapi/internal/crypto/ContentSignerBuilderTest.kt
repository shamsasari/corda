package net.corda.nodeapi.internal.crypto

import net.corda.core.crypto.Crypto
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.InvalidKeyException

class ContentSignerBuilderTest {
    companion object {
        private const val entropy = "20200723"
    }

    @Test
    fun `should build content signer for valid eddsa key`() {
        val signatureScheme = Crypto.EDDSA_ED25519_SHA512
        val provider = Crypto.findProvider(signatureScheme.providerName)
        val issuerKeyPair = Crypto.deriveKeyPairFromEntropy(signatureScheme, BigInteger(entropy))
        ContentSignerBuilder.build(signatureScheme, issuerKeyPair.private, provider)
    }

    @Test
    fun `should fail to build content signer for incorrect key type`() {
        val signatureScheme = Crypto.EDDSA_ED25519_SHA512
        val provider = Crypto.findProvider(signatureScheme.providerName)
        val issuerKeyPair = Crypto.deriveKeyPairFromEntropy(Crypto.ECDSA_SECP256R1_SHA256, BigInteger(entropy))
        assertThatExceptionOfType(InvalidKeyException::class.java)
                .isThrownBy {
                    ContentSignerBuilder.build(signatureScheme, issuerKeyPair.private, provider)
                }
                .withMessage("Incorrect key type EC for signature scheme NONEwithEdDSA")
    }
}