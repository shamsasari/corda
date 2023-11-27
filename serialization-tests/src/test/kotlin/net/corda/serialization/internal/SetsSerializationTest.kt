package net.corda.serialization.internal

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.util.DefaultClassResolver
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.node.services.statemachine.DataSessionMessage
import net.corda.nodeapi.internal.serialization.kryo.kryoMagic
import net.corda.testing.core.SerializationExtension
import net.corda.testing.internal.kryoSpecific
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.ByteArrayOutputStream
import java.util.Collections

@ExtendWith(SerializationExtension::class)
class SetsSerializationTest {
    private companion object {
        val javaEmptySetClass = Collections.emptySet<Any>().javaClass
    }

    @Test
	fun `check set can be serialized as root of serialization graph`() {
        assertEqualAfterRoundTripSerialization(emptySet<Int>())
        assertEqualAfterRoundTripSerialization(setOf(1))
        assertEqualAfterRoundTripSerialization(setOf(1, 2))
    }

    @Test
	fun `check set can be serialized as part of DataSessionMessage`() {
        run {
            val sessionData = DataSessionMessage(setOf(1).serialize())
            assertEqualAfterRoundTripSerialization(sessionData)
            assertEquals(setOf(1), sessionData.payload.deserialize())
        }
        run {
            val sessionData = DataSessionMessage(setOf(1, 2).serialize())
            assertEqualAfterRoundTripSerialization(sessionData)
            assertEquals(setOf(1, 2), sessionData.payload.deserialize())
        }
        run {
            val sessionData = DataSessionMessage(emptySet<Int>().serialize())
            assertEqualAfterRoundTripSerialization(sessionData)
            assertEquals(emptySet<Int>(), sessionData.payload.deserialize())
        }
    }

    @Test
	fun `check empty set serialises as Java emptySet`() = kryoSpecific("Checks Kryo header properties") {
        val nameID = 0
        val serializedForm = emptySet<Int>().serialize()
        val output = ByteArrayOutputStream().apply {
            kryoMagic.writeTo(this)
            SectionId.ALT_DATA_AND_STOP.writeTo(this)
            write(DefaultClassResolver.NAME + 2)
            write(nameID)
            write(javaEmptySetClass.name.toAscii())
            write(Kryo.NOT_NULL.toInt())
        }
        assertArrayEquals(output.toByteArray(), serializedForm.bytes)
    }

    open class P
    class VarOfP(var p: Set<P>)

    /*
    See CORDA-2860.

    When a class has a var parameter of type Set<out T>, Kotlin generates getters and setters with the following (Java) signatures:

    public Set<T> getP();
    public void setP(Set<? extends T> p);

    The PropertyDescriptor.validate method used to check that the return type of the getter was a supertype of the parameter type of the
    setter. Unfortunately, Set<T> is not a strict supertype of Set<? extends T>, so this check would fail, throwing an exception.

    We now check only for compatibility of the erased classes, so the call to propertyDescriptors() below should now succeed, returning the
    property descriptor for "p".
     */
    @Test
	fun `type variance on setter getter pair does not fail validation`() {
        assertThat(VarOfP::class.java.accessPropertyDescriptors()).containsKey("p")
    }

}

