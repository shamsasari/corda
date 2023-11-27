package net.corda.nodeapi.internal.crypto

import net.corda.core.crypto.internal.AliasPrivateKey
import net.corda.coretesting.internal.stubs.CertificateStoreStubs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AliasPrivateKeyTest {

    @TempDir
    private lateinit var tempFolder: Path

    @Test
	fun `store AliasPrivateKey entry and cert to keystore`() {
        val alias = "01234567890"
        val aliasPrivateKey = AliasPrivateKey(alias)
        val certificatesDirectory = tempFolder
        val signingCertStore = CertificateStoreStubs.Signing.withCertificatesDirectory(
                certificatesDirectory,
                "keystorepass").get(createNew = true)
        signingCertStore.query {
            setPrivateKey(alias, aliasPrivateKey, listOf(NOT_YET_REGISTERED_MARKER_KEYS_AND_CERTS.ECDSAR1_CERT), "entrypassword")
        }
        // We can retrieve the certificate.
        assertTrue { signingCertStore.contains(alias) }
        // We can retrieve the certificate.
        assertEquals(NOT_YET_REGISTERED_MARKER_KEYS_AND_CERTS.ECDSAR1_CERT, signingCertStore[alias])
        assertEquals(aliasPrivateKey, signingCertStore.query { getPrivateKey(alias, "entrypassword") })
    }
}
