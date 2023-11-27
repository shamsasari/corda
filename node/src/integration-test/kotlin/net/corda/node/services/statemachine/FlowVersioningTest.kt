package net.corda.node.services.statemachine

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.node.internal.NodeFlowManager
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.NodeBasedTest
import net.corda.testing.node.internal.startFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FlowVersioningTest : NodeBasedTest() {
    @Test
	fun `getFlowContext returns the platform version for core flows`() {
        val bobFlowManager = NodeFlowManager()
        val alice = startNode(ALICE_NAME, platformVersion = 2)
        val bob = startNode(BOB_NAME, platformVersion = 3, flowManager = bobFlowManager)
        bobFlowManager.registerInitiatedCoreFlowFactory(PretendInitiatingCoreFlow::class, ::PretendInitiatedCoreFlow)
        val (alicePlatformVersionAccordingToBob, bobPlatformVersionAccordingToAlice) = alice.services.startFlow(
                PretendInitiatingCoreFlow(bob.info.singleIdentity())).resultFuture.getOrThrow()
        assertThat(alicePlatformVersionAccordingToBob).isEqualTo(2)
        assertThat(bobPlatformVersionAccordingToAlice).isEqualTo(3)
    }

    @InitiatingFlow
    private class PretendInitiatingCoreFlow(val initiatedParty: Party) : FlowLogic<Pair<Int, Int>>() {
        @Suspendable
        override fun call(): Pair<Int, Int> {
            val session = initiateFlow(initiatedParty)
            return try {
                // Get counterparty flow info before we receive Alice's data, to ensure the flow is still open
                val bobPlatformVersionAccordingToAlice = session.getCounterpartyFlowInfo().flowVersion
                // Execute receive() outside of the Pair constructor to avoid Kotlin/Quasar instrumentation bug.
                val alicePlatformVersionAccordingToBob = session.receive<Int>().unwrap { it }
                Pair(
                        alicePlatformVersionAccordingToBob,
                        bobPlatformVersionAccordingToAlice
                )
            } finally {
                session.close()
            }
        }
    }

    private class PretendInitiatedCoreFlow(val otherSideSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() = otherSideSession.send(otherSideSession.getCounterpartyFlowInfo().flowVersion)
    }

}