package net.corda.node.internal

import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.contextLogger
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.enclosedCordapp
import org.junit.jupiter.api.AfterEach
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NodeUnloadHandlerTests {
    companion object {
        val registerLatch = CountDownLatch(1)
        val shutdownLatch = CountDownLatch(1)
    }

    private val mockNet = InternalMockNetwork(cordappsForAllNodes = listOf(enclosedCordapp()), notarySpecs = emptyList())

    @AfterEach
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
	fun `should be able to register run on stop lambda`() {
        val node = mockNet.createNode()
        registerLatch.await()  // Make sure the handler is registered on node start up
        node.dispose()
        assertTrue("Timed out waiting for AbstractNode to invoke the test service shutdown callback", shutdownLatch.await(30, TimeUnit.SECONDS))
    }

    @Suppress("unused")
    @CordaService
    class RunOnStopTestService(serviceHub: ServiceHub) : SingletonSerializeAsToken() {
        companion object {
            private val log = contextLogger()
        }

        init {
            serviceHub.registerUnloadHandler(this::shutdown)
            registerLatch.countDown()
        }

        private fun shutdown() {
            log.info("shutting down")
            shutdownLatch.countDown()
        }
    }
}
