package net.corda.serialization.internal

import net.corda.serialization.internal.amqp.custom.ThrowableSerializer
import net.corda.serialization.internal.amqp.testutils.testDefaultFactoryNoEvolution
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SerializationCompatibilityTests {
    @Test
	fun `fingerprint is stable`() {
        val factory = testDefaultFactoryNoEvolution().apply { register(ThrowableSerializer(this)) }
        assertThat(factory.get(Exception::class.java).typeDescriptor.toString()).isEqualTo("net.corda:ApZ2a/36VVskaoDZMbiZ8A==")
    }
}
