package net.corda.testing.common.internal

import net.corda.core.internal.isStatic
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestTemplateInvocationContext
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider
import org.junit.platform.commons.support.AnnotationSupport.findAnnotation
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.util.stream.IntStream
import java.util.stream.Stream

internal class ParameterizedClassTestExtension : TestTemplateInvocationContextProvider {
    override fun supportsTestTemplate(context: ExtensionContext): Boolean {
        return findAnnotation(context.testMethod, ParameterizedClassTest::class.java).isPresent
    }

    override fun provideTestTemplateInvocationContexts(context: ExtensionContext): Stream<TestTemplateInvocationContext> {
        val testClass = context.requiredTestClass
        val rawData = testClass.getDeclaredMethod("data").accessible().invoke(null)
        return when (rawData) {
            is Map<*, *> -> {
                val data = rawData.entries.associateBy(
                        { testClass.getDeclaredField(it.key.toString()).accessible() },
                        { checkNotNull((it.value as? Collection<*>)?.toList()) { "data mapping of field ${it.key} must be to a Collection" } }
                )
                require(data.values.map { it.size }.toSet().size == 1) { "All the fields must have the same number of values" }
                convertToContextStream(data)
            }
            is Collection<*> -> singleFieldContextStream(testClass, rawData.toList())
            is Array<*> -> singleFieldContextStream(testClass, rawData.toList())
            else -> throw IllegalStateException("'data' method must either return a Map<String, Collection<Any?>> or a Collection<Any?>")
        }
    }

    private fun singleFieldContextStream(testClass: Class<*>, values: List<*>): Stream<TestTemplateInvocationContext> {
        val fields = testClass.declaredFields.filter { !it.isStatic }
        checkNotNull(fields.size == 1) {
            "${testClass.name} has more than one instance field. Change 'data' to return a Map of field name to values"
        }
        val field = fields[0].accessible()
        return convertToContextStream(mapOf(field to values))
    }

    private fun convertToContextStream(data: Map<Field, List<*>>): Stream<TestTemplateInvocationContext> {
        return IntStream
                .range(0, data.values.first().size)
                .mapToObj { valueIndex -> InvocationContext(data.mapValues { it.value[valueIndex] }) }
    }

    private fun <T : AccessibleObject> T.accessible(): T = apply { isAccessible = true }


    private class InvocationContext(private val fieldValues: Map<Field, Any?>) : TestTemplateInvocationContext, BeforeEachCallback {
        override fun getDisplayName(invocationIndex: Int): String {
            return "$invocationIndex: ${fieldValues.entries.joinToString { "${it.key.name}=${it.value}" }}"
        }

        override fun getAdditionalExtensions(): List<Extension> = listOf(this)

        override fun beforeEach(context: ExtensionContext) {
            val testInstance = context.requiredTestInstance
            for ((field, value) in fieldValues) {
                field.set(testInstance, value)
            }
        }
    }
}
