package net.corda.testing.common.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach

class ParameterizedClassTestTests {
    companion object {
        @JvmStatic
        private fun data(): Map<String, List<Any?>> {
            return mapOf(
                    "nullableField" to listOf("a", "b", null),
                    "nonNullableField" to listOf("x", "y", "z"),
                    "intField" to listOf(1, 2, 3),
            )
        }

        private val capturedValues = ArrayList<List<Any?>>()

        @AfterAll
        @JvmStatic
        fun `assert field injection occurred`() {
            assertThat(capturedValues).containsExactly(
                    listOf("a", "x", 1),
                    listOf("b", "y", 2),
                    listOf(null, "z", 3),
            )
        }
    }

    // Fields are intentially declared not in the same order as `data`
    private lateinit var nonNullableField: String
    private var nullableField: String? = null
    private var intField: Int = 0

    @BeforeEach
    fun `assert field injection occurs before BeforeEach`() {
        assertThat(::nonNullableField.isInitialized).isTrue()
    }

    @ParameterizedClassTest
    fun `first run`() {
        capturedValues += listOf(nullableField, nonNullableField, intField)
    }
}
