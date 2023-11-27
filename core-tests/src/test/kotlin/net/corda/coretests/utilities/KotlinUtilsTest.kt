package net.corda.coretests.utilities

import com.esotericsoftware.kryo.KryoException
import net.corda.core.crypto.random63BitValue
import net.corda.core.serialization.ClassWhitelist
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.internal.checkpointDeserialize
import net.corda.core.serialization.internal.checkpointSerialize
import net.corda.core.utilities.transient
import net.corda.nodeapi.internal.serialization.kryo.KRYO_CHECKPOINT_CONTEXT
import net.corda.serialization.internal.CheckpointSerializationContextImpl
import net.corda.testing.core.SerializationExtension
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

object EmptyWhitelist : ClassWhitelist {
    override fun hasListed(type: Class<*>): Boolean = false
}

@ExtendWith(SerializationExtension::class)
@Disabled("TODO JDK17: class cast exception")
class KotlinUtilsTest {
    private val KRYO_CHECKPOINT_NOWHITELIST_CONTEXT = CheckpointSerializationContextImpl(
            javaClass.classLoader,
            EmptyWhitelist,
            emptyMap(),
            true,
            null)

    @Test
	fun `transient property which is null`() {
        val test = NullTransientProperty()
        test.transientValue
        test.transientValue
        assertThat(test.evalCount).isEqualTo(1)
    }

    @Test
	fun `checkpointing a transient property with non-capturing lambda`() {
        val original = NonCapturingTransientProperty()
        val originalVal = original.transientVal
        val copy = original.checkpointSerialize(context = KRYO_CHECKPOINT_CONTEXT).checkpointDeserialize(context = KRYO_CHECKPOINT_CONTEXT)
        val copyVal = copy.transientVal
        assertThat(copyVal).isNotEqualTo(originalVal)
        assertThat(copy.transientVal).isEqualTo(copyVal)
    }

    @Test
	fun `deserialise transient property with non-capturing lambda`() {
        val original = NonCapturingTransientProperty()
        assertThatExceptionOfType(KryoException::class.java).isThrownBy {
            original.checkpointSerialize(context = KRYO_CHECKPOINT_CONTEXT).checkpointDeserialize(context = KRYO_CHECKPOINT_NOWHITELIST_CONTEXT)
        }.withMessage("is not annotated or on the whitelist, so cannot be used in serialization")
    }

    @Test
	fun `checkpointing a transient property with capturing lambda`() {
        val original = CapturingTransientProperty("Hello")
        val originalVal = original.transientVal
        val copy = original.checkpointSerialize(context = KRYO_CHECKPOINT_CONTEXT).checkpointDeserialize(context = KRYO_CHECKPOINT_CONTEXT)
        val copyVal = copy.transientVal
        assertThat(copyVal).isNotEqualTo(originalVal)
        assertThat(copy.transientVal).isEqualTo(copyVal)
        assertThat(copy.transientVal).startsWith("Hello")
    }

    @Test
	fun `deserialise transient property with capturing lambda`() {
        val original = CapturingTransientProperty("Hello")
        assertThatExceptionOfType(KryoException::class.java).isThrownBy {
            original.checkpointSerialize(context = KRYO_CHECKPOINT_CONTEXT).checkpointDeserialize(context = KRYO_CHECKPOINT_NOWHITELIST_CONTEXT)
        }.withMessage("is not annotated or on the whitelist, so cannot be used in serialization")
    }

    private class NullTransientProperty {
        var evalCount = 0
        val transientValue by transient {
            evalCount++
            null
        }
    }

    @CordaSerializable
    private class NonCapturingTransientProperty {
        val transientVal by transient { random63BitValue() }
    }

    @CordaSerializable
    private class CapturingTransientProperty(val prefix: String, val seed: Long = random63BitValue()) {
        val transientVal by transient { prefix + seed + random63BitValue() }
    }
}
