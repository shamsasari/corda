package net.corda.serialization.internal.amqp

import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.amqp.custom.OptionalSerializer
import net.corda.serialization.internal.amqp.testutils.TestSerializationOutput
import net.corda.serialization.internal.amqp.testutils.deserialize
import net.corda.serialization.internal.amqp.testutils.testDefaultFactory
import net.corda.serialization.internal.carpenter.ClassCarpenterImpl
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.Optional

class OptionalSerializationTests {

    @Test
    fun `java optionals should serialize`() {
        val factory = SerializerFactoryBuilder.build(AllWhitelist,
                ClassCarpenterImpl(AllWhitelist, ClassLoader.getSystemClassLoader())
        )
        factory.register(OptionalSerializer(factory))
        val obj = Optional.ofNullable("YES")
        val bytes = TestSerializationOutput(true, factory).serialize(obj)
        val deserializerFactory = testDefaultFactory().apply {
            register(OptionalSerializer(this))
        }

        val deserialized = DeserializationInput(factory).deserialize(bytes)
        val deserialized2 = DeserializationInput(deserializerFactory).deserialize(bytes)
        Assert.assertThat(deserialized, `is`(equalTo(deserialized2)))
        Assert.assertThat(obj, `is`(equalTo(deserialized2)))
    }
}
