package net.corda.testing.node.internal

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.toFuture
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.testing.internal.chooseIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.After
import org.junit.Test

/**
 * Test based on the example given as an answer to this SO question:
 *
 * https://stackoverflow.com/questions/48166626/how-can-an-acceptor-flow-in-corda-be-isolated-for-unit-testing/
 *
 * but using the `registerFlowFactory` method implemented in response to https://r3-cev.atlassian.net/browse/CORDA-916
 */
class TestResponseFlowInIsolation {

    private val network: MockNetwork = MockNetwork(MockNetworkParameters(cordappsForAllNodes = cordappsForPackages()))
    private val a = network.createNode()
    private val b = network.createNode()

    @After
    fun tearDown() = network.stopNodes()

    // This is the real implementation of Initiator.
    @InitiatingFlow
    open class Initiator(private val counterparty: Party) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val session = initiateFlow(counterparty)
            session.send("goodString")
        }
    }

    // This is the response flow that we want to isolate for testing.
    @InitiatedBy(Initiator::class)
    class Responder(private val counterpartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val string = counterpartySession.receive<String>().unwrap { contents -> contents }
            if (string != "goodString") {
                throw FlowException("String did not contain the expected message.")
            }
        }
    }

    // This is a fake implementation of Initiator to check how Responder responds to non-golden-path scenarios.
    @InitiatingFlow
    class BadInitiator(private val counterparty: Party): FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val session = initiateFlow(counterparty)
            session.send("badString")
        }
    }

    @Test
	fun test() {
        // This method returns the Responder flow object used by node B.
        // We tell node B to respond to BadInitiator with Responder.
        val initiatedResponderFlowFuture = b.registerInitiatedFlow(BadInitiator::class.java, Responder::class.java).toFuture()

        // We run the BadInitiator flow on node A.
        val flow = BadInitiator(b.info.chooseIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        future.get()

        // We check that the invocation of the Responder flow object has caused an ExecutionException.
        val initiatedResponderFlow = initiatedResponderFlowFuture.getOrThrow()
        val initiatedResponderFlowResultFuture = initiatedResponderFlow.stateMachine.resultFuture

        assertThatExceptionOfType(FlowException::class.java)
                .isThrownBy { initiatedResponderFlowResultFuture.getOrThrow() }
                .withMessage("String did not contain the expected message.")
    }
}
