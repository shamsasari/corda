package net.corda.serialization.internal.amqp

import junit.framework.TestCase.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import net.corda.core.serialization.ConstructorForDeserialization
import net.corda.serialization.internal.amqp.testutils.*
import net.corda.serialization.internal.model.ConfigurableLocalTypeModel
import net.corda.serialization.internal.model.LocalPropertyInformation
import net.corda.serialization.internal.model.LocalTypeInformation
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions
import java.io.NotSerializableException
import java.util.*

class PrivatePropertyTests {

    private val registry = TestDescriptorBasedSerializerRegistry()
    private val factory = testDefaultFactoryNoEvolution(registry)
    val typeModel = ConfigurableLocalTypeModel(WhitelistBasedTypeModelConfiguration(factory.whitelist, factory))

    @Test
	fun testWithOnePrivateProperty() {
        data class C(private val b: String)

        val c1 = C("Pants are comfortable sometimes")
        val c2 = DeserializationInput(factory).deserialize(SerializationOutput(factory).serialize(c1))
        assertEquals(c1, c2)
    }

    @Test
	fun testWithOnePrivatePropertyBoolean() {
        data class C(private val b: Boolean)

        C(false).apply {
            assertEquals(this, DeserializationInput(factory).deserialize(SerializationOutput(factory).serialize(this)))
        }
    }

    @Test
	fun testWithOnePrivatePropertyNullableNotNull() {
        data class C(private val b: String?)

        val c1 = C("Pants are comfortable sometimes")
        val c2 = DeserializationInput(factory).deserialize(SerializationOutput(factory).serialize(c1))
        assertEquals(c1, c2)
    }

    @Test
	fun testWithOnePrivatePropertyNullableNull() {
        data class C(private val b: String?)

        val c1 = C(null)
        val c2 = DeserializationInput(factory).deserialize(SerializationOutput(factory).serialize(c1))
        assertEquals(c1, c2)
    }

    @Test
	fun testWithOnePublicOnePrivateProperty() {
        data class C(val a: Int, private val b: Int)

        val c1 = C(1, 2)
        val c2 = DeserializationInput(factory).deserialize(SerializationOutput(factory).serialize(c1))
        assertEquals(c1, c2)
    }

    @Test
	fun testWithInheritance() {
        open class B(val a: String, protected val b: String)
        class D (a: String, b: String) : B (a, b) {
            override fun equals(other: Any?): Boolean = when (other) {
                is D -> other.a == a && other.b == b
                else -> false
            }
            override fun hashCode(): Int = Objects.hash(a, b)
        }

        val d1 = D("clump", "lump")
        val d2 = DeserializationInput(factory).deserialize(SerializationOutput(factory).serialize(d1))

        assertEquals(d1, d2)
    }

    @Test
	fun testMultiArgSetter() {
        @Suppress("UNUSED")
        data class C(private var a: Int, var b: Int) {
            // This will force the serialization engine to use getter / setter
            // instantiation for the object rather than construction
            @ConstructorForDeserialization
            constructor() : this(0, 0)

            fun setA(a: Int, @Suppress("UNUSED_PARAMETER") b: Int) { this.a = a }
            fun getA() = a
        }

        val c1 = C(33, 44)
        val c2 = DeserializationInput(factory).deserialize(SerializationOutput(factory).serialize(c1))
        assertEquals(0, c2.getA())
        assertEquals(44, c2.b)
    }

    @Test
	fun testBadTypeArgSetter() {
        @Suppress("UNUSED")
        data class C(private var a: Int, val b: Int) {
            @ConstructorForDeserialization
            constructor() : this(0, 0)

            fun setA(a: String) { this.a = a.toInt() }
            fun getA() = a
        }

        val c1 = C(33, 44)
        Assertions.assertThatThrownBy {
            SerializationOutput(factory).serialize(c1)
        }.isInstanceOf(NotSerializableException::class.java).hasMessageContaining(
                "Defined setter for parameter a takes parameter of type class java.lang.String " +
                        "yet underlying type is int")
    }

    @Test
	fun testWithOnePublicOnePrivateProperty2() {
        data class C(val a: Int, private val b: Int)

        val c1 = C(1, 2)
        val schemaAndBlob = SerializationOutput(factory).serializeAndReturnSchema(c1)
        assertEquals(1, schemaAndBlob.schema.types.size)

        val typeInformation = typeModel.inspect(C::class.java)
        assertTrue(typeInformation is LocalTypeInformation.Composable)
        typeInformation as LocalTypeInformation.Composable

        assertEquals(2, typeInformation.properties.size)
        assertTrue(typeInformation.properties["a"] is LocalPropertyInformation.ConstructorPairedProperty)
        assertTrue(typeInformation.properties["b"] is LocalPropertyInformation.PrivateConstructorPairedProperty)
    }

    @Test
	fun testGetterMakesAPublicReader() {
        data class C(val a: Int, private val b: Int) {
            @Suppress("UNUSED")
            fun getB() = b
        }

        val c1 = C(1, 2)
        val schemaAndBlob = SerializationOutput(factory).serializeAndReturnSchema(c1)
        assertEquals(1, schemaAndBlob.schema.types.size)


        val typeInformation = typeModel.inspect(C::class.java)
        assertTrue(typeInformation is LocalTypeInformation.Composable)
        typeInformation as LocalTypeInformation.Composable

        assertEquals(2, typeInformation.properties.size)
        assertTrue(typeInformation.properties["a"] is LocalPropertyInformation.ConstructorPairedProperty)
        assertTrue(typeInformation.properties["b"] is LocalPropertyInformation.ConstructorPairedProperty)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
	fun testNested() {
        data class Inner(private val a: Int)
        data class Outer(private val i: Inner)

        val c1 = Outer(Inner(1010101))
        val output = SerializationOutput(factory).serializeAndReturnSchema(c1)

        val serializersByDescriptor = registry.contents

        // Inner and Outer
        assertEquals(2, serializersByDescriptor.size)

        val c2 = DeserializationInput(factory).deserialize(output.obj)

        assertEquals(c1, c2)
    }

    //
    // Reproduces CORDA-1134
    //
    @Suppress("UNCHECKED_CAST")
    @Test
	fun allCapsProprtyNotPrivate() {
        data class C (val CCC: String)
        val typeInformation = typeModel.inspect(C::class.java)

        assertTrue(typeInformation is LocalTypeInformation.Composable)
        typeInformation as LocalTypeInformation.Composable

        assertEquals(1, typeInformation.properties.size)
        assertTrue(typeInformation.properties["CCC"] is LocalPropertyInformation.ConstructorPairedProperty)
    }

}