package net.corda.traderdemo

import net.corda.client.rpc.CordaRPCClient
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.millis
import net.corda.finance.DOLLARS
import net.corda.finance.USD
import net.corda.finance.workflows.getCashBalance
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.flows.CashPaymentFlow
import net.corda.node.services.Permissions.Companion.all
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.testing.core.BOC_NAME
import net.corda.testing.core.DUMMY_BANK_A_NAME
import net.corda.testing.core.DUMMY_BANK_B_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.*
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import net.corda.testing.node.internal.FINANCE_CORDAPPS
import net.corda.testing.node.internal.assertUncompletedCheckpoints
import net.corda.testing.node.internal.poll
import net.corda.traderdemo.flow.CommercialPaperIssueFlow
import net.corda.traderdemo.flow.SellerFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.concurrent.Executors

class TraderDemoTest {
    @Test
	fun `runs trader demo`() {
        val demoUser = User("demo", "demo", setOf(startFlow<SellerFlow>(), all()))
        val bankUser = User("user1", "test", permissions = setOf(
                startFlow<CashIssueFlow>(),
                startFlow<CashPaymentFlow>(),
                startFlow<CommercialPaperIssueFlow>(),
                all()))
        driver(DriverParameters(
                startNodesInProcess = true,
                inMemoryDB = false,
                cordappsForAllNodes = FINANCE_CORDAPPS + TestCordapp.findCordapp("net.corda.traderdemo.flow")
        )) {
            val (nodeA, nodeB, bankNode) = listOf(
                    startNode(providedName = DUMMY_BANK_A_NAME, rpcUsers = listOf(demoUser)),
                    startNode(providedName = DUMMY_BANK_B_NAME, rpcUsers = listOf(demoUser)),
                    startNode(providedName = BOC_NAME, rpcUsers = listOf(bankUser))
            ).map { (it.getOrThrow() as InProcess) }

            val (nodeARpc, nodeBRpc) = listOf(nodeA, nodeB).map {
                val client = CordaRPCClient(it.rpcAddress)
                client.start(demoUser.username, demoUser.password).proxy
            }
            val nodeBankRpc = let {
                val client = CordaRPCClient(bankNode.rpcAddress)
                client.start(bankUser.username, bankUser.password).proxy
            }

            val clientA = TraderDemoClientApi(nodeARpc)
            val clientB = TraderDemoClientApi(nodeBRpc)
            val clientBank = TraderDemoClientApi(nodeBankRpc)

            val originalACash = clientA.cashCount // A has random number of issued amount
            val expectedBCash = clientB.cashCount + 1
            val expectedPaper = listOf(clientA.commercialPaperCount + 1, clientB.commercialPaperCount)

            clientBank.runIssuer(amount = 100.DOLLARS, buyerName = nodeA.services.myInfo.singleIdentity().name, sellerName = nodeB.services.myInfo.singleIdentity().name)
            clientB.runSeller(buyerName = nodeA.services.myInfo.singleIdentity().name, amount = 5.DOLLARS)

            assertThat(clientA.cashCount).isGreaterThan(originalACash)
            assertThat(clientB.cashCount).isEqualTo(expectedBCash)
            // Wait until A receives the commercial paper
            val executor = Executors.newScheduledThreadPool(1)
            try {
                poll(executor, "A to be notified of the commercial paper", pollInterval = 100.millis) {
                    val actualPaper = listOf(clientA.commercialPaperCount, clientB.commercialPaperCount)
                    if (actualPaper == expectedPaper) Unit else null
                }.getOrThrow()
            } finally {
                executor.shutdownNow()
            }
            assertThat(clientA.dollarCashBalance).isEqualTo(95.DOLLARS)
            assertThat(clientB.dollarCashBalance).isEqualTo(5.DOLLARS)
        }
    }

    @Test
	fun `Test restart node during flow works properly`() {
        driver(DriverParameters(
                startNodesInProcess = false,
                inMemoryDB = false,
                cordappsForAllNodes = FINANCE_CORDAPPS + TestCordapp.findCordapp("net.corda.traderdemo.flow")
        )) {
            val (buyer, seller, bank) = listOf(
                    startNode(providedName = DUMMY_BANK_A_NAME),
                    startNode(providedName = DUMMY_BANK_B_NAME),
                    startNode(providedName = BOC_NAME)
            ).map { it.getOrThrow() }
            TraderDemoClientApi(bank.rpc).runIssuer(amount = 100.DOLLARS, buyerName = DUMMY_BANK_A_NAME, sellerName = DUMMY_BANK_B_NAME)
            val saleFuture = seller.rpc.startFlow(::SellerFlow, buyer.nodeInfo.singleIdentity(), 5.DOLLARS).returnValue
            buyer.rpc.stateMachinesFeed().updates.toBlocking().first() // wait until initiated flow starts
            buyer.stop()
            assertUncompletedCheckpoints(DUMMY_BANK_A_NAME, 1)
            val buyer2 = startNode(providedName = DUMMY_BANK_A_NAME, customOverrides = mapOf("p2pAddress" to buyer.p2pAddress.toString())).getOrThrow()
            saleFuture.getOrThrow()
            assertThat(buyer2.rpc.getCashBalance(USD)).isEqualTo(95.DOLLARS)
            assertThat(seller.rpc.getCashBalance(USD)).isEqualTo(5.DOLLARS)
        }
    }
}
