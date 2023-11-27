package net.corda.serialization.internal

import com.esotericsoftware.kryo.DefaultSerializer
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.KryoSerializable
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.util.DefaultClassResolver
import com.esotericsoftware.kryo.util.MapReferenceResolver
import net.corda.core.contracts.TransactionVerificationException.UntrustedAttachmentsException
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.DEPLOYED_CORDAPP_UPLOADER
import net.corda.core.node.services.AttachmentStorage
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.internal.AttachmentsClassLoader
import net.corda.core.serialization.internal.CheckpointSerializationContext
import net.corda.coretesting.internal.rigorousMock
import net.corda.node.services.attachments.NodeAttachmentTrustCalculator
import net.corda.nodeapi.internal.serialization.kryo.CordaClassResolver
import net.corda.nodeapi.internal.serialization.kryo.CordaKryo
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.internal.TestingNamedCacheFactory
import net.corda.testing.internal.services.InternalMockAttachmentStorage
import net.corda.testing.services.MockAttachmentStorage
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.URL
import java.sql.Connection
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@CordaSerializable
enum class Foo {
    Bar {
        override val value = 0
    },
    Stick {
        override val value = 1
    };

    abstract val value: Int
}

enum class BadFood {
    Mud {
        override val value = -1
    };

    abstract val value: Int
}

@CordaSerializable
enum class Simple {
    Easy
}

enum class BadSimple {
    Nasty
}

@CordaSerializable
open class Element

open class SubElement : Element()

class SubSubElement : SubElement()

abstract class AbstractClass

interface Interface

@CordaSerializable
interface SerializableInterface

interface SerializableSubInterface : SerializableInterface

class NotSerializable

class SerializableViaInterface : SerializableInterface

open class SerializableViaSubInterface : SerializableSubInterface

class SerializableViaSuperSubInterface : SerializableViaSubInterface()

@CordaSerializable
class CustomSerializable : KryoSerializable {
    override fun read(kryo: Kryo?, input: Input?) {
    }

    override fun write(kryo: Kryo?, output: Output?) {
    }
}

@CordaSerializable
@DefaultSerializer(DefaultSerializableSerializer::class)
class DefaultSerializable

class DefaultSerializableSerializer : Serializer<DefaultSerializable>() {
    override fun write(kryo: Kryo, output: Output, obj: DefaultSerializable) {
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out DefaultSerializable>): DefaultSerializable {
        return DefaultSerializable()
    }
}

class CordaClassResolverTests {
    private companion object {
        val emptyListClass = listOf<Any>().javaClass
        val emptySetClass = setOf<Any>().javaClass
        val emptyMapClass = mapOf<Any, Any>().javaClass
        val ISOLATED_CONTRACTS_JAR_PATH: URL = CordaClassResolverTests::class.java.getResource("/isolated.jar")!!
    }

    private val emptyWhitelistContext: CheckpointSerializationContext = CheckpointSerializationContextImpl(this.javaClass.classLoader, EmptyWhitelist, emptyMap(), true, null)
    private val allButBlacklistedContext: CheckpointSerializationContext = CheckpointSerializationContextImpl(this.javaClass.classLoader, AllButBlacklisted, emptyMap(), true, null)

    @Test
	fun `Annotation on enum works for specialised entries`() {
        CordaClassResolver(emptyWhitelistContext).getRegistration(Foo.Bar::class.java)
    }

    @Test
    fun `Unannotated specialised enum does not work`() {
        assertThatExceptionOfType(KryoException::class.java).isThrownBy {
            CordaClassResolver(emptyWhitelistContext).getRegistration(BadFood.Mud::class.java)
        }
    }

    @Test
	fun `Annotation on simple enum works`() {
        CordaClassResolver(emptyWhitelistContext).getRegistration(Simple.Easy::class.java)
    }

    @Test
    fun `Unannotated simple enum does not work`() {
        assertThatExceptionOfType(KryoException::class.java).isThrownBy {
            CordaClassResolver(emptyWhitelistContext).getRegistration(BadSimple.Nasty::class.java)
        }
    }

    @Test
	fun `Annotation on array element works`() {
        val values = arrayOf(Element())
        CordaClassResolver(emptyWhitelistContext).getRegistration(values.javaClass)
    }

    @Test
    fun `Unannotated array elements do not work`() {
        val values = arrayOf(NotSerializable())
        assertThatExceptionOfType(KryoException::class.java).isThrownBy {
            CordaClassResolver(emptyWhitelistContext).getRegistration(values.javaClass)
        }
    }

    @Test
	fun `Annotation not needed on abstract class`() {
        CordaClassResolver(emptyWhitelistContext).getRegistration(AbstractClass::class.java)
    }

    @Test
	fun `Annotation not needed on interface`() {
        CordaClassResolver(emptyWhitelistContext).getRegistration(Interface::class.java)
    }

    @Test
	fun `Calling register method on modified Kryo does not consult the whitelist`() {
        val kryo = CordaKryo(CordaClassResolver(emptyWhitelistContext))
        kryo.register(NotSerializable::class.java)
    }

    @Test
    fun `Calling register method on unmodified Kryo does consult the whitelist`() {
        val kryo = Kryo(CordaClassResolver(emptyWhitelistContext), MapReferenceResolver())
        assertThatExceptionOfType(KryoException::class.java).isThrownBy {
            kryo.register(NotSerializable::class.java)
        }
    }

    @Test
    fun `Annotation is needed without whitelisting`() {
        assertThatExceptionOfType(KryoException::class.java).isThrownBy {
            CordaClassResolver(emptyWhitelistContext).getRegistration(NotSerializable::class.java)
        }
    }

    @Test
	fun `Annotation is not needed with whitelisting`() {
        val resolver = CordaClassResolver(emptyWhitelistContext.withWhitelisted(NotSerializable::class.java))
        resolver.getRegistration(NotSerializable::class.java)
    }

    @Test
	fun `Annotation not needed on Object`() {
        CordaClassResolver(emptyWhitelistContext).getRegistration(Object::class.java)
    }

    @Test
	fun `Annotation not needed on primitive`() {
        CordaClassResolver(emptyWhitelistContext).getRegistration(Integer.TYPE)
    }

    @Test
    fun `Annotation does not work for custom serializable`() {
        assertThatExceptionOfType(KryoException::class.java).isThrownBy {
            CordaClassResolver(emptyWhitelistContext).getRegistration(CustomSerializable::class.java)
        }
    }

    @Test
    fun `Annotation does not work in conjunction with Kryo annotation`() {
        assertThatExceptionOfType(KryoException::class.java).isThrownBy {
            CordaClassResolver(emptyWhitelistContext).getRegistration(DefaultSerializable::class.java)
        }
    }

    private fun importJar(storage: AttachmentStorage, uploader: String = DEPLOYED_CORDAPP_UPLOADER) = ISOLATED_CONTRACTS_JAR_PATH.openStream().use { storage.importAttachment(it, uploader, "") }

    @Test
    fun `Annotation does not work in conjunction with AttachmentClassLoader annotation`() {
        val storage = InternalMockAttachmentStorage(MockAttachmentStorage())
        val attachmentTrustCalculator = NodeAttachmentTrustCalculator(storage, TestingNamedCacheFactory())
        val attachmentHash = importJar(storage)
        val classLoader = AttachmentsClassLoader(arrayOf(attachmentHash).map { storage.openAttachment(it)!! }, testNetworkParameters(), SecureHash.zeroHash, { attachmentTrustCalculator.calculate(it) })
        val attachedClass = Class.forName("net.corda.isolated.contracts.AnotherDummyContract", true, classLoader)
        assertThatExceptionOfType(KryoException::class.java).isThrownBy {
            CordaClassResolver(emptyWhitelistContext).getRegistration(attachedClass)
        }
    }

    @Test
    fun `Attempt to load contract attachment with untrusted uploader should fail with UntrustedAttachmentsException`() {
        val storage = InternalMockAttachmentStorage(MockAttachmentStorage())
        val attachmentTrustCalculator = NodeAttachmentTrustCalculator(storage, TestingNamedCacheFactory())
        val attachmentHash = importJar(storage, "some_uploader")
        val classLoader = AttachmentsClassLoader(arrayOf(attachmentHash).map { storage.openAttachment(it)!! }, testNetworkParameters(), SecureHash.zeroHash, { attachmentTrustCalculator.calculate(it) })
        val attachedClass = Class.forName("net.corda.isolated.contracts.AnotherDummyContract", true, classLoader)
        assertThatExceptionOfType(UntrustedAttachmentsException::class.java).isThrownBy {
            CordaClassResolver(emptyWhitelistContext).getRegistration(attachedClass)
        }
    }

    @Test
	fun `Annotation is inherited from interfaces`() {
        CordaClassResolver(emptyWhitelistContext).getRegistration(SerializableViaInterface::class.java)
        CordaClassResolver(emptyWhitelistContext).getRegistration(SerializableViaSubInterface::class.java)
    }

    @Test
	fun `Annotation is inherited from superclass`() {
        CordaClassResolver(emptyWhitelistContext).getRegistration(SubElement::class.java)
        CordaClassResolver(emptyWhitelistContext).getRegistration(SubSubElement::class.java)
        CordaClassResolver(emptyWhitelistContext).getRegistration(SerializableViaSuperSubInterface::class.java)
    }

    @Test
	fun `Check blacklisted class`() {
        val resolver = CordaClassResolver(allButBlacklistedContext)
        // HashSet is blacklisted.
        assertThatIllegalStateException().isThrownBy {
            resolver.getRegistration(HashSet::class.java)
        }.withMessage("Class java.util.HashSet is blacklisted, so it cannot be used in serialization.")
    }

    @Test
	fun `Kotlin EmptyList not registered`() {
        val resolver = CordaClassResolver(allButBlacklistedContext)
        assertNull(resolver.getRegistration(emptyListClass))
    }

    @Test
	fun `Kotlin EmptyList registers as Java emptyList`() {
        val javaEmptyListClass = Collections.emptyList<Any>().javaClass
        val kryo = rigorousMock<Kryo>()
        val resolver = CordaClassResolver(allButBlacklistedContext).apply { setKryo(kryo) }
        doReturn(DefaultSerializableSerializer()).whenever(kryo).getDefaultSerializer(javaEmptyListClass)
        doReturn(false).whenever(kryo).references
        doReturn(false).whenever(kryo).references = any()
        val registration = resolver.registerImplicit(emptyListClass)
        assertNotNull(registration)
        assertEquals(javaEmptyListClass, registration.type)
        assertEquals(DefaultClassResolver.NAME.toInt(), registration.id)
        verify(kryo).getDefaultSerializer(javaEmptyListClass)
        assertEquals(registration, resolver.getRegistration(emptyListClass))
    }

    @Test
	fun `Kotlin EmptySet not registered`() {
        val resolver = CordaClassResolver(allButBlacklistedContext)
        assertNull(resolver.getRegistration(emptySetClass))
    }

    @Test
	fun `Kotlin EmptySet registers as Java emptySet`() {
        val javaEmptySetClass = Collections.emptySet<Any>().javaClass
        val kryo = rigorousMock<Kryo>()
        val resolver = CordaClassResolver(allButBlacklistedContext).apply { setKryo(kryo) }
        doReturn(DefaultSerializableSerializer()).whenever(kryo).getDefaultSerializer(javaEmptySetClass)
        doReturn(false).whenever(kryo).references
        doReturn(false).whenever(kryo).references = any()
        val registration = resolver.registerImplicit(emptySetClass)
        assertNotNull(registration)
        assertEquals(javaEmptySetClass, registration.type)
        assertEquals(DefaultClassResolver.NAME.toInt(), registration.id)
        verify(kryo).getDefaultSerializer(javaEmptySetClass)
        assertEquals(registration, resolver.getRegistration(emptySetClass))
    }

    @Test
	fun `Kotlin EmptyMap not registered`() {
        val resolver = CordaClassResolver(allButBlacklistedContext)
        assertNull(resolver.getRegistration(emptyMapClass))
    }

    @Test
	fun `Kotlin EmptyMap registers as Java emptyMap`() {
        val javaEmptyMapClass = Collections.emptyMap<Any, Any>().javaClass
        val kryo = rigorousMock<Kryo>()
        val resolver = CordaClassResolver(allButBlacklistedContext).apply { setKryo(kryo) }
        doReturn(DefaultSerializableSerializer()).whenever(kryo).getDefaultSerializer(javaEmptyMapClass)
        doReturn(false).whenever(kryo).references
        doReturn(false).whenever(kryo).references = any()
        val registration = resolver.registerImplicit(emptyMapClass)
        assertNotNull(registration)
        assertEquals(javaEmptyMapClass, registration.type)
        assertEquals(DefaultClassResolver.NAME.toInt(), registration.id)
        verify(kryo).getDefaultSerializer(javaEmptyMapClass)
        assertEquals(registration, resolver.getRegistration(emptyMapClass))
    }

    open class SubHashSet<E> : HashSet<E>()

    @Test
	fun `Check blacklisted subclass`() {
        val resolver = CordaClassResolver(allButBlacklistedContext)
        // SubHashSet extends the blacklisted HashSet.
        assertThatIllegalStateException().isThrownBy {
            resolver.getRegistration(SubHashSet::class.java)
        }.withMessage("The superclass java.util.HashSet of net.corda.serialization.internal.CordaClassResolverTests\$SubHashSet is blacklisted, so it cannot be used in serialization.")
    }

    class SubSubHashSet<E> : SubHashSet<E>()

    @Test
	fun `Check blacklisted subsubclass`() {
        val resolver = CordaClassResolver(allButBlacklistedContext)
        // SubSubHashSet extends SubHashSet, which extends the blacklisted HashSet.
        assertThatIllegalStateException().isThrownBy {
            resolver.getRegistration(SubSubHashSet::class.java)
        }.withMessage("The superclass java.util.HashSet of net.corda.serialization.internal.CordaClassResolverTests\$SubSubHashSet is blacklisted, so it cannot be used in serialization.")
    }

    class ConnectionImpl(private val connection: Connection) : Connection by connection

    @Test
	fun `Check blacklisted interface impl`() {
        val resolver = CordaClassResolver(allButBlacklistedContext)
        // ConnectionImpl implements blacklisted Connection.
        assertThatIllegalStateException().isThrownBy {
            resolver.getRegistration(ConnectionImpl::class.java)
        }.withMessage("The superinterface java.sql.Connection of net.corda.serialization.internal.CordaClassResolverTests\$ConnectionImpl is blacklisted, so it cannot be used in serialization.")
    }

    interface SubConnection : Connection
    class SubConnectionImpl(private val subConnection: SubConnection) : SubConnection by subConnection

    @Test
	fun `Check blacklisted super-interface impl`() {
        val resolver = CordaClassResolver(allButBlacklistedContext)
        // SubConnectionImpl implements SubConnection, which extends the blacklisted Connection.
        assertThatIllegalStateException().isThrownBy {
            resolver.getRegistration(SubConnectionImpl::class.java)
        }.withMessage("The superinterface java.sql.Connection of net.corda.serialization.internal.CordaClassResolverTests\$SubConnectionImpl is blacklisted, so it cannot be used in serialization.")
    }

    @Test
	fun `Check forcibly allowed`() {
        val resolver = CordaClassResolver(allButBlacklistedContext)
        // LinkedHashSet is allowed for serialization.
        resolver.getRegistration(LinkedHashSet::class.java)
    }

    @CordaSerializable
    class CordaSerializableHashSet<E> : HashSet<E>()

    @Test
	fun `Check blacklist precedes CordaSerializable`() {
        val resolver = CordaClassResolver(allButBlacklistedContext)
        // CordaSerializableHashSet is @CordaSerializable, but extends the blacklisted HashSet.
        assertThatIllegalStateException().isThrownBy {
            resolver.getRegistration(CordaSerializableHashSet::class.java)
        }.withMessage("The superclass java.util.HashSet of net.corda.serialization.internal.CordaClassResolverTests\$CordaSerializableHashSet is blacklisted, so it cannot be used in serialization.")
    }
}
