package net.corda.nodeapi.internal.crypto

import net.corda.core.internal.validate
import net.corda.nodeapi.internal.loadDevCaTrustStore
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.rules.TemporaryFolder
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate

class DevCertificatesTest {
    private companion object {
        const val OLD_DEV_KEYSTORE_PASS = "password"
        const val OLD_NODE_DEV_KEYSTORE_FILE_NAME = "nodekeystore.jks"
    }

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
	fun `create server certificate in keystore for SSL`() {
        // given
        val newTrustStore = loadDevCaTrustStore()
        val newTrustRoot = newTrustStore[X509Utilities.CORDA_ROOT_CA]
        val newTrustAnchor = TrustAnchor(newTrustRoot, null)

        val oldNodeCaKeyStore = loadKeyStore(javaClass.classLoader.getResourceAsStream("regression-test/$OLD_NODE_DEV_KEYSTORE_FILE_NAME"), OLD_DEV_KEYSTORE_PASS)
        val oldX509Certificates = oldNodeCaKeyStore.getCertificateChain(X509Utilities.CORDA_CLIENT_CA).map {
            it as X509Certificate
        }.toTypedArray()

        val certPath = X509Utilities.buildCertPath(*oldX509Certificates)

        // when
        certPath.validate(setOf(newTrustAnchor))

        // then no exception is thrown
    }
}
