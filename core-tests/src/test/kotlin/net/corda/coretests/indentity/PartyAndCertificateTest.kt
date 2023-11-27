package net.corda.coretests.indentity

import com.google.common.jimfs.Configuration.unix
import com.google.common.jimfs.Jimfs
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.entropyToKeyPair
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.nodeapi.internal.crypto.X509KeyStore
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.testing.core.SerializationEnvironmentRule
import net.corda.testing.core.getTestPartyAndCertificate
import net.corda.coretesting.internal.DEV_ROOT_CA
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.assertFailsWith

class PartyAndCertificateTest {
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    @Before
    fun setUp() {
        // Register providers before creating Jimfs filesystem. JimFs creates an SSHD instance which
        // register BouncyCastle and EdDSA provider separately, which wrecks havoc.
        Crypto.registerProviders()
    }

    @Test
	fun `reject a path with no roles`() {
        val path = X509Utilities.buildCertPath(DEV_ROOT_CA.certificate)
        assertFailsWith<IllegalArgumentException> { PartyAndCertificate(path) }
    }

    @Test
	fun `kryo serialisation`() {
        val original = getTestPartyAndCertificate(Party(
                CordaX500Name(organisation = "Test Corp", locality = "Madrid", country = "ES"),
                entropyToKeyPair(BigInteger.valueOf(83)).public))
        val copy = original.serialize().deserialize()
        assertThat(copy).isEqualTo(original).isNotSameAs(original)
        assertThat(copy.certPath).isEqualTo(original.certPath)
        assertThat(copy.certificate).isEqualTo(original.certificate)
    }

    @Test
	fun `jdk serialization`() {
        val identity = getTestPartyAndCertificate(Party(
                CordaX500Name(organisation = "Test Corp", locality = "Madrid", country = "ES"),
                entropyToKeyPair(BigInteger.valueOf(83)).public))
        val original = identity.certificate
        val alias = identity.name.toString()
        val storePassword = "test"
        Jimfs.newFileSystem(unix()).use {
            val keyStoreFile = it.getPath("/serialization_test.jks")

            X509KeyStore.fromFile(keyStoreFile, storePassword, createNew = true).update {
                setCertificate(alias, original)
            }

            // Load the key store back in again
            val copy = X509KeyStore.fromFile(keyStoreFile, storePassword).getCertificate(alias)
            assertThat(copy).isEqualTo(original)
        }
    }
}
