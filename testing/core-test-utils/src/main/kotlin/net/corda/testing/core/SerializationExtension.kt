package net.corda.testing.core

import net.corda.core.internal.staticField
import net.corda.core.serialization.SerializationFactory
import net.corda.core.serialization.internal.SerializationEnvironment
import net.corda.core.serialization.internal.effectiveSerializationEnv
import net.corda.coretesting.internal.asTestContextEnv
import net.corda.coretesting.internal.createTestSerializationEnv
import net.corda.coretesting.internal.inVMExecutors
import net.corda.coretesting.internal.rigorousMock
import net.corda.coretesting.internal.testThreadFactory
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMConnector
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A test serialization [org.junit.jupiter.api.extension.Extension] implementation for use in tests
 */
class SerializationExtension : InvocationInterceptor {
    companion object {
        init {
            // Can't turn it off, and it creates threads that do serialization, so hack it:
            InVMConnector::class.staticField<ExecutorService>("threadPoolExecutor").value = rigorousMock<ExecutorService>()
                    .also {
                doAnswer {
                    inVMExecutors.computeIfAbsent(effectiveSerializationEnv) {
                        Executors.newCachedThreadPool(testThreadFactory(true)) // Close enough to what InVMConnector makes normally.
                    }.execute(it.arguments[0] as Runnable)
                }.whenever(it).execute(any())
            }
        }
    }

    private lateinit var env: SerializationEnvironment

    val serializationFactory: SerializationFactory get() = env.serializationFactory

    override fun interceptTestMethod(invocation: InvocationInterceptor.Invocation<Void>,
                                     invocationContext: ReflectiveInvocationContext<Method>,
                                     extensionContext: ExtensionContext) {
        env = createTestSerializationEnv()
        env.asTestContextEnv { invocation.proceed() }
    }
}
