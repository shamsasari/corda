package net.corda.node.modes.draining

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.Permissions
import net.corda.nodeapi.exceptions.RejectedCommandException
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.driver.internal.incrementalPortAllocation
import net.corda.testing.node.User
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.Test

class RpcFlowsDrainingModeTest {

    private val portAllocation = incrementalPortAllocation()
    private val user = User("mark", "dadada", setOf(Permissions.all()))
    private val users = listOf(user)

    @Test
	fun `flows draining mode rejects start flows commands through rpc`() {
        driver(DriverParameters(startNodesInProcess = false, portAllocation = portAllocation, notarySpecs = emptyList())) {
            startNode(rpcUsers = users).getOrThrow().rpc.apply {
                setFlowsDrainingModeEnabled(true)

                val error: Throwable? = catchThrowable { startFlow(RpcFlowsDrainingModeTest::NoOpFlow) }

                assertThat(error).isNotNull()
                assertThat(error!!).isInstanceOf(RejectedCommandException::class.java)
            }
        }
    }

    @StartableByRPC
    class NoOpFlow : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            println("NO OP!")
        }
    }
}
