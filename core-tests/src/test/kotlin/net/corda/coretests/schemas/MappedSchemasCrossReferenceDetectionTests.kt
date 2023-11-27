package net.corda.coretests.schemas

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.MappedSchemaValidator.fieldsFromOtherMappedSchema
import net.corda.core.schemas.MappedSchemaValidator.methodsFromOtherMappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.finance.schemas.CashSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.persistence.*

class MappedSchemasCrossReferenceDetectionTests {

    object GoodSchema : MappedSchema(schemaFamily = CashSchema.javaClass, version = 1, mappedTypes = listOf(GoodSchema.State::class.java)) {
        @Entity
        class State(
                @Column
                var id: String
        ) : PersistentState()
    }

    object BadSchema : MappedSchema(schemaFamily = CashSchema.javaClass, version = 1, mappedTypes = listOf(BadSchema.State::class.java)) {
        @Entity
        class State(
                @Column
                var id: String,

                @JoinColumns(JoinColumn(name = "itid"), JoinColumn(name = "outid"))
                @OneToOne
                @MapsId
                var other: GoodSchema.State
        ) : PersistentState()
    }

    object TrickySchema : MappedSchema(schemaFamily = CashSchema.javaClass, version = 1, mappedTypes = listOf(TrickySchema.State::class.java)) {
        @Entity
        class State(
                @Column
                var id: String,

                //the field is a cross-reference to other MappedSchema however the field is not persistent (no JPA annotation)
                var other: GoodSchema.State
        ) : PersistentState()
    }

    object PoliteSchema : MappedSchema(schemaFamily = CashSchema.javaClass, version = 1, mappedTypes = listOf(PoliteSchema.State::class.java)) {
        @Entity
        class State(
                @Column
                var id: String,

                @Transient
                var other: GoodSchema.State
        ) : PersistentState()
    }

    @Test
	fun `no cross reference to other schema`() {
        assertThat(fieldsFromOtherMappedSchema(GoodSchema)).isEmpty()
        assertThat(methodsFromOtherMappedSchema(GoodSchema)).isEmpty()
    }

    @Test
	fun `cross reference to other schema is detected`() {
        assertThat(fieldsFromOtherMappedSchema(BadSchema)).isNotEmpty
        assertThat(methodsFromOtherMappedSchema(BadSchema)).isEmpty()
    }

    @Test
	fun `cross reference via non JPA field is allowed`() {
        assertThat(fieldsFromOtherMappedSchema(TrickySchema)).isEmpty()
        assertThat(methodsFromOtherMappedSchema(TrickySchema)).isEmpty()
    }

    @Test
	fun `cross reference via transient field is allowed`() {
        assertThat(fieldsFromOtherMappedSchema(PoliteSchema)).isEmpty()
        assertThat(methodsFromOtherMappedSchema(PoliteSchema)).isEmpty()
    }

    @Test
	fun `no cross reference to other schema java`() {
        assertThat(fieldsFromOtherMappedSchema(GoodSchemaJavaV1())).isEmpty()
        assertThat(methodsFromOtherMappedSchema(GoodSchemaJavaV1())).isEmpty()
    }

    @Test
	fun `cross reference to other schema is detected java`() {
        assertThat(fieldsFromOtherMappedSchema(BadSchemaJavaV1())).isEmpty()
        assertThat(methodsFromOtherMappedSchema(BadSchemaJavaV1())).isNotEmpty
    }

    @Test
	fun `cross reference to other schema via field is detected java`() {
        assertThat(fieldsFromOtherMappedSchema(BadSchemaNoGetterJavaV1())).isNotEmpty
        assertThat(methodsFromOtherMappedSchema(BadSchemaNoGetterJavaV1())).isEmpty()
    }

    @Test
	fun `cross reference via non JPA field is allowed java`() {
        assertThat(fieldsFromOtherMappedSchema(TrickySchemaJavaV1())).isEmpty()
        assertThat(methodsFromOtherMappedSchema(TrickySchemaJavaV1())).isEmpty()
    }

    @Test
	fun `cross reference via transient field is allowed java`() {
        assertThat(fieldsFromOtherMappedSchema(PoliteSchemaJavaV1())).isEmpty()
        assertThat(methodsFromOtherMappedSchema(PoliteSchemaJavaV1())).isEmpty()
    }
}