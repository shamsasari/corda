package net.corda.node.services.statemachine

import co.paralleluniverse.fibers.Suspendable
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.internal.InputStreamAndHash
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.startFlow
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.config.MB
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.contracts.DummyContract
import net.corda.testing.contracts.DummyState
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.TestIdentity
import net.corda.testing.core.dummyCommand
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import net.corda.testing.node.internal.DUMMY_CONTRACTS_CORDAPP
import net.corda.testing.node.internal.enclosedCordapp
import org.junit.Ignore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Check that we can add lots of large attachments to a transaction and that it works OK, e.g. does not hit the
 * transaction size limit (which should only consider the hashes).
 */
@Ignore("ENT-5679: This test triggers OOM errors")
class LargeTransactionsTest {
    private companion object {
        val BOB = TestIdentity(BOB_NAME, 80).party
    }

    @StartableByRPC
    @InitiatingFlow
    class SendLargeTransactionFlow(private val hash1: SecureHash,
                                   private val hash2: SecureHash,
                                   private val hash3: SecureHash,
                                   private val hash4: SecureHash) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val notary = serviceHub.networkParameters.notaries.first().identity
            val tx = TransactionBuilder(notary)
                    .addOutputState(DummyState(), DummyContract.PROGRAM_ID)
                    .addCommand(dummyCommand(ourIdentity.owningKey))
                    .addAttachment(hash1)
                    .addAttachment(hash2)
                    .addAttachment(hash3)
                    .addAttachment(hash4)
            val stx = serviceHub.signInitialTransaction(tx, ourIdentity.owningKey)
            // Send to the other side and wait for it to trigger resolution from us.
            val bob = serviceHub.identityService.wellKnownPartyFromX500Name(BOB.name)!!
            val bobSession = initiateFlow(bob)
            subFlow(SendTransactionFlow(bobSession, stx))
            bobSession.receive<Unit>()
        }
    }

    @InitiatedBy(SendLargeTransactionFlow::class)
    @Suppress("UNUSED")
    class ReceiveLargeTransactionFlow(private val otherSide: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            subFlow(ReceiveTransactionFlow(otherSide))
            // Unblock the other side by sending some dummy object (Unit is fine here as it's a singleton).
            otherSide.send(Unit)
        }
    }

    @Test
	fun checkCanSendLargeTransactions() {
        // These 4 attachments yield a transaction that's got >10mb attached, so it'd push us over the Artemis
        // max message size.
        val bigFile1 = InputStreamAndHash.createInMemoryTestZip(3.MB.toInt(), 0, "a")
        val bigFile2 = InputStreamAndHash.createInMemoryTestZip(3.MB.toInt(), 1, "b")
        val bigFile3 = InputStreamAndHash.createInMemoryTestZip(3.MB.toInt(), 2, "c")
        val bigFile4 = InputStreamAndHash.createInMemoryTestZip(3.MB.toInt(), 3, "d")

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = listOf(DUMMY_CONTRACTS_CORDAPP, enclosedCordapp()),
                networkParameters = testNetworkParameters(maxMessageSize = 15.MB.toInt(), maxTransactionSize = 13.MB.toInt()),
                premigrateH2Database = false
        )) {
            val rpcUser = User("admin", "admin", setOf("ALL"))
            val (alice, _) = listOf(ALICE_NAME, BOB_NAME).map { startNode(providedName = it, rpcUsers = listOf(rpcUser)) }.transpose().getOrThrow()
            CordaRPCClient(alice.rpcAddress).use(rpcUser.username, rpcUser.password) {
                val hash1 = it.proxy.uploadAttachment(bigFile1.inputStream)
                val hash2 = it.proxy.uploadAttachment(bigFile2.inputStream)
                val hash3 = it.proxy.uploadAttachment(bigFile3.inputStream)
                val hash4 = it.proxy.uploadAttachment(bigFile4.inputStream)
                assertEquals(hash1, bigFile1.sha256)
                // Should not throw any exceptions.
                it.proxy.startFlow(::SendLargeTransactionFlow, hash1, hash2, hash3, hash4).returnValue.getOrThrow()
            }
        }
    }
}