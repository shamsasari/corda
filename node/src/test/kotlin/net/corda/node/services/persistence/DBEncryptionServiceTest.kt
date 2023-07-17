package net.corda.node.services.persistence

import net.corda.node.services.persistence.DBEncryptionService.DBEncryptionKey
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.core.TestIdentity
import net.corda.testing.internal.configureDatabase
import net.corda.testing.node.MockServices
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.UUID

class DBEncryptionServiceTest {
    private val identity = TestIdentity.fresh("me").party
    private lateinit var database: CordaPersistence
    private lateinit var encryptionService: DBEncryptionService

    @Before
    fun setUp() {
        val dataSourceProps = MockServices.makeTestDataSourceProperties()
        database = configureDatabase(dataSourceProps, DatabaseConfig(), { null }, { null })
        encryptionService = DBEncryptionService(database)
        encryptionService.start(identity)
    }

    @After
    fun cleanUp() {
        database.close()
    }

    @Test(timeout = 300_000)
    fun `same instance can decrypt ciphertext`() {
        val ciphertext = encryptionService.encrypt("Hello World".toByteArray())
        val plaintext = encryptionService.decrypt(ciphertext)
        assertThat(String(plaintext)).isEqualTo("Hello World")
    }

    @Test(timeout = 300_000)
    fun `encypting twice produces different ciphertext`() {
        val plaintext = "Hello".toByteArray()
        assertThat(encryptionService.encrypt(plaintext)).isNotEqualTo(encryptionService.encrypt(plaintext))
    }

    @Test(timeout = 300_000)
    fun `ciphertext can be decrypted after restart`() {
        val ciphertext = encryptionService.encrypt("Hello World".toByteArray())
        encryptionService = DBEncryptionService(database)
        encryptionService.start(identity)
        val plaintext = encryptionService.decrypt(ciphertext)
        assertThat(String(plaintext)).isEqualTo("Hello World")
    }

    @Test(timeout = 300_000)
    fun `ciphertext cannot be decrypted if the key used is deleted`() {
        val ciphertext = encryptionService.encrypt("Hello World".toByteArray())
        val keyId = ByteBuffer.wrap(ciphertext).getUUID()
        val deletedCount = database.transaction {
            session.createQuery("DELETE FROM ${DBEncryptionKey::class.java.name} k WHERE k.keyId = :keyId")
                    .setParameter("keyId", keyId)
                    .executeUpdate()
        }
        assertThat(deletedCount).isEqualTo(1)

        encryptionService = DBEncryptionService(database)
        encryptionService.start(identity)
        assertThatIllegalArgumentException().isThrownBy {
            encryptionService.decrypt(ciphertext)
        }
    }

    @Test(timeout = 300_000)
    fun `ciphertext cannot be decrypted if forced to use a different key`() {
        val ciphertext = ByteBuffer.wrap(encryptionService.encrypt("Hello World".toByteArray()))
        val keyId = ciphertext.getUUID()
        val anotherKeyId = database.transaction {
            session.createQuery("SELECT keyId FROM ${DBEncryptionKey::class.java.name} k WHERE k.keyId != :keyId", UUID::class.java)
                    .setParameter("keyId", keyId)
                    .setMaxResults(1)
                    .singleResult
        }

        ciphertext.position(0)
        ciphertext.putUUID(anotherKeyId)

        encryptionService = DBEncryptionService(database)
        encryptionService.start(identity)
        assertThatExceptionOfType(GeneralSecurityException::class.java).isThrownBy {
            encryptionService.decrypt(ciphertext.array())
        }
    }
}
