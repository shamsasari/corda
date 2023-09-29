package net.corda.node.modes.draining

import co.paralleluniverse.fibers.Suspendable
import net.corda.RpcInfo
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.concurrent.transpose
import net.corda.core.internal.packageName
import net.corda.core.messaging.startFlow
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.node.services.Permissions.Companion.all
import net.corda.testMessage.MESSAGE_CONTRACT_PROGRAM_ID
import net.corda.testMessage.Message
import net.corda.testMessage.MessageContract
import net.corda.testMessage.MessageState
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.driver.internal.incrementalPortAllocation
import net.corda.testing.node.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class FlowsDrainingModeContentionTest {
    private val portAllocation = incrementalPortAllocation()
    private val user = User("mark", "dadada", setOf(all()))
    private val users = listOf(user)

    private var executor: ScheduledExecutorService? = null

    @Before
    fun setup() {
        executor = Executors.newSingleThreadScheduledExecutor()
    }

    @After
    fun cleanUp() {
        executor!!.shutdown()
    }

    @Test(timeout=300_000)
    @Ignore("TODO JDK17:Fixme - timed out")
	fun `draining mode does not deadlock with acks between 2 nodes`() {
        val message = "Ground control to Major Tom"
        driver(DriverParameters(
                startNodesInProcess = true,
                portAllocation = portAllocation,
                extraCordappPackagesToScan = listOf(MessageState::class.packageName)
        )) {
            val (nodeA, nodeB) = listOf(ALICE_NAME, BOB_NAME)
                    .map { startNode(providedName = it, rpcUsers = users) }
                    .transpose()
                    .getOrThrow()

            val nodeARpcInfo = RpcInfo(nodeA.rpcAddress, user.username, user.password)
            val flow = nodeA.rpc.startFlow(::ProposeTransactionAndWaitForCommit, message, nodeARpcInfo, nodeB.nodeInfo.singleIdentity(), defaultNotaryIdentity)
            val committedTx = flow.returnValue.getOrThrow()

            committedTx.inputs
            committedTx.tx.outputs
            assertThat(committedTx.tx.outputsOfType<MessageState>().single().message.value).isEqualTo(message)
        }
    }
}

@StartableByRPC
@InitiatingFlow
class ProposeTransactionAndWaitForCommit(private val data: String,
                                         private val myRpcInfo: RpcInfo,
                                         private val counterParty: Party,
                                         private val notary: Party) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val session = initiateFlow(counterParty)
        val messageState = MessageState(message = Message(data), by = ourIdentity)
        val command = Command(MessageContract.Commands.Send(), messageState.participants.map { it.owningKey })
        val transaction = TransactionBuilder(notary)
        transaction.withItems(StateAndContract(messageState, MESSAGE_CONTRACT_PROGRAM_ID), command)
        val signedTx = serviceHub.signInitialTransaction(transaction)

        subFlow(SendTransactionFlow(session, signedTx))
        session.send(myRpcInfo)

        return subFlow(ReceiveFinalityFlow(session, expectedTxId = signedTx.id))
    }
}

@InitiatedBy(ProposeTransactionAndWaitForCommit::class)
class SignTransactionTriggerDrainingModeAndFinality(private val session: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val tx = subFlow(ReceiveTransactionFlow(session))
        val signedTx = serviceHub.addSignature(tx)
        val initiatingRpcInfo = session.receive<RpcInfo>().unwrap { it }

        triggerDrainingModeForInitiatingNode(initiatingRpcInfo)

        subFlow(FinalityFlow(signedTx, session))
    }

    private fun triggerDrainingModeForInitiatingNode(initiatingRpcInfo: RpcInfo) {
        CordaRPCClient(initiatingRpcInfo.address).start(initiatingRpcInfo.username, initiatingRpcInfo.password).use {
            it.proxy.setFlowsDrainingModeEnabled(true)
        }
    }
}
