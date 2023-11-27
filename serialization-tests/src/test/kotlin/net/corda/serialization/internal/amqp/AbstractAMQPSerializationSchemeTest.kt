package net.corda.serialization.internal.amqp

import net.corda.core.internal.toSynchronised
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.deserialize
import net.corda.core.utilities.ByteSequence
import net.corda.serialization.internal.AllWhitelist
import net.corda.serialization.internal.CordaSerializationMagic
import net.corda.serialization.internal.SerializationContextImpl
import net.corda.serialization.internal.amqp.testutils.serializationProperties
import net.corda.coretesting.internal.createTestSerializationEnv
import org.hamcrest.CoreMatchers
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.Matchers
import org.junit.Assert
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.util.concurrent.ThreadLocalRandom
import java.util.stream.IntStream

class AbstractAMQPSerializationSchemeTest {

    private val serializationEnvironment = createTestSerializationEnv()

    @Test
	fun `number of cached factories must be bounded by maxFactories`() {
        val genesisContext = SerializationContextImpl(
                ByteSequence.of(byteArrayOf('c'.toByte(), 'o'.toByte(), 'r'.toByte(), 'd'.toByte(), 'a'.toByte(), 0.toByte(), 0.toByte(), 1.toByte())),
                ClassLoader.getSystemClassLoader(),
                AllWhitelist,
                serializationProperties,
                false,
                SerializationContext.UseCase.RPCClient,
                null)


        val factory = SerializerFactoryBuilder.build(TESTING_CONTEXT.whitelist, TESTING_CONTEXT.deserializationClassLoader)
        val maxFactories = 512
        val backingMap = AccessOrderLinkedHashMap<SerializationFactoryCacheKey, SerializerFactory>({ maxFactories }).toSynchronised()
        val scheme = object : AbstractAMQPSerializationScheme(emptySet(), emptySet(), backingMap, createSerializerFactoryFactory()) {
            override fun rpcClientSerializerFactory(context: SerializationContext): SerializerFactory {
                return factory
            }

            override fun rpcServerSerializerFactory(context: SerializationContext): SerializerFactory {
                return factory
            }

            override fun canDeserializeVersion(magic: CordaSerializationMagic, target: SerializationContext.UseCase): Boolean {
                return true
            }

        }

        IntStream.range(0, 2048).parallel().forEach {
            val context = if (ThreadLocalRandom.current().nextBoolean()) {
                genesisContext.withClassLoader(URLClassLoader(emptyArray()))
            } else {
                genesisContext
            }
            val testString = "TEST${ThreadLocalRandom.current().nextInt()}"
            val serialized = scheme.serialize(testString, context)
            val deserialized = serialized.deserialize(context = context, serializationFactory = serializationEnvironment.serializationFactory)
            Assert.assertThat(testString, `is`(deserialized))
            Assert.assertThat(backingMap.size, `is`(Matchers.lessThanOrEqualTo(maxFactories)))
        }
        Assert.assertThat(backingMap.size, CoreMatchers.`is`(Matchers.lessThanOrEqualTo(maxFactories)))
    }
}



