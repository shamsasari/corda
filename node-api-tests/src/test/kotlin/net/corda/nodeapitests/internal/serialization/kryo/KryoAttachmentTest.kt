package net.corda.nodeapitests.internal.serialization.kryo

import net.corda.core.crypto.SecureHash
import net.corda.core.serialization.EncodingWhitelist
import net.corda.core.serialization.internal.CheckpointSerializationContext
import net.corda.core.serialization.internal.checkpointDeserialize
import net.corda.core.serialization.internal.checkpointSerialize
import net.corda.coretesting.internal.rigorousMock
import net.corda.node.services.persistence.NodeAttachmentService
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.CheckpointSerializationContextImpl
import net.corda.serialization.internal.CordaSerializationEncoding
import net.corda.testing.core.internal.CheckpointSerializationExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import java.io.InputStream

@RunWith(Parameterized::class)
@ExtendWith(CheckpointSerializationExtension::class)
class KryoAttachmentTest(private val compression: CordaSerializationEncoding?) {
    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun compression() = arrayOf<CordaSerializationEncoding?>(null) + CordaSerializationEncoding.values()
    }

    private lateinit var context: CheckpointSerializationContext

    @BeforeEach
    fun setup() {
        context = CheckpointSerializationContextImpl(
                javaClass.classLoader,
                AllWhitelist,
                emptyMap(),
                true,
                compression,
                rigorousMock<EncodingWhitelist>().also {
                    if (compression != null) doReturn(true).whenever(it).acceptEncoding(compression)
                })
    }

    @Test
    fun `HashCheckingStream (de)serialize`() {
        val rubbish = ByteArray(12345) { (it * it * 0.12345).toInt().toByte() }
        val readRubbishStream: InputStream = NodeAttachmentService.HashCheckingStream(
                SecureHash.sha256(rubbish),
                rubbish.size,
                rubbish.inputStream()
        ).checkpointSerialize(context).checkpointDeserialize(context)
        for (i in 0..12344) {
            assertEquals(rubbish[i], readRubbishStream.read().toByte())
        }
        assertEquals(-1, readRubbishStream.read())
    }
}
