package net.corda.serialization.internal

import net.corda.core.crypto.Crypto
import net.corda.core.serialization.SerializationContext.UseCase.*
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.internal.CheckpointSerializationDefaults
import net.corda.core.serialization.internal.checkpointSerialize
import net.corda.core.serialization.serialize
import net.corda.testing.core.SerializationExtension
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.security.PrivateKey
import kotlin.test.assertTrue

@ExtendWith(SerializationExtension::class)
@RunWith(Parameterized::class)
class PrivateKeySerializationTest(private val privateKey: PrivateKey, private val testName: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{1}")
        fun data(): Collection<Array<Any>> {
            val privateKeys: List<PrivateKey> = Crypto.supportedSignatureSchemes().filterNot { Crypto.COMPOSITE_KEY === it }
                    .map { Crypto.generateKeyPair(it).private }

            return privateKeys.map { arrayOf<Any>(it, PrivateKeySerializationTest::class.java.simpleName + "-" + it.javaClass.simpleName) }
        }
    }

    @Test
	fun `passed with expected UseCases`() {
        assertTrue { privateKey.serialize(context = SerializationDefaults.STORAGE_CONTEXT).bytes.isNotEmpty() }
        assertTrue { privateKey.checkpointSerialize(context = CheckpointSerializationDefaults.CHECKPOINT_CONTEXT).bytes.isNotEmpty() }
    }

    @Test
	fun `failed with wrong UseCase`() {
        assertThatThrownBy { privateKey.serialize(context = SerializationDefaults.P2P_CONTEXT) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("UseCase '$P2P' is not 'Storage")
    }
}