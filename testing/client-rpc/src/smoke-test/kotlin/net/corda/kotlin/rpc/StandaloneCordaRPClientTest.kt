package net.corda.kotlin.rpc

import com.google.common.hash.Hashing
import com.google.common.hash.HashingInputStream
import net.corda.client.rpc.CordaRPCConnection
import net.corda.client.rpc.PermissionException
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.InputStreamAndHash
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.StateMachineUpdate
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.messaging.vaultTrackBy
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.DEFAULT_PAGE_NUM
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Sort
import net.corda.core.node.services.vault.SortAttribute
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.minutes
import net.corda.core.utilities.seconds
import net.corda.finance.DOLLARS
import net.corda.finance.POUNDS
import net.corda.finance.SWISS_FRANCS
import net.corda.finance.USD
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueFlow
import net.corda.finance.flows.CashPaymentFlow
import net.corda.finance.workflows.getCashBalance
import net.corda.finance.workflows.getCashBalances
import net.corda.java.rpc.StandaloneCordaRPCJavaClientTest
import net.corda.nodeapi.internal.config.User
import net.corda.sleeping.SleepingFlow
import net.corda.smoketesting.NodeConfig
import net.corda.smoketesting.NodeProcess
import org.apache.commons.io.output.NullOutputStream.NULL_OUTPUT_STREAM
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Currency
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StandaloneCordaRPClientTest {
    private companion object {
        private val log = contextLogger()
        val superUser = User("superUser", "test", permissions = setOf("ALL"))
        val nonUser = User("nonUser", "test", permissions = emptySet())
        val rpcUser = User("rpcUser", "test", permissions = setOf("InvokeRpc.startFlow", "InvokeRpc.killFlow"))
        val flowUser = User("flowUser", "test", permissions = setOf("StartFlow.net.corda.finance.flows.CashIssueFlow"))
        val port = AtomicInteger(15200)
        const val attachmentSize = 2116
        val timeout = 60.seconds
    }

    private lateinit var factory: NodeProcess.Factory
    private lateinit var notary: NodeProcess
    private lateinit var rpcProxy: CordaRPCOps
    private lateinit var connection: CordaRPCConnection
    private lateinit var notaryNode: NodeInfo
    private lateinit var notaryNodeIdentity: Party

    private val notaryConfig = NodeConfig(
            legalName = CordaX500Name(organisation = "Notary Service", locality = "Zurich", country = "CH"),
            p2pPort = port.andIncrement,
            rpcPort = port.andIncrement,
            rpcAdminPort = port.andIncrement,
            isNotary = true,
            users = listOf(superUser, nonUser, rpcUser, flowUser)
    )

    @BeforeEach
    fun setUp() {
        factory = NodeProcess.Factory()
        StandaloneCordaRPCJavaClientTest.copyCordapps(factory, notaryConfig)
        notary = factory.create(notaryConfig)
        connection = notary.connect(superUser)
        rpcProxy = connection.proxy
        notaryNode = fetchNotaryIdentity()
        notaryNodeIdentity = rpcProxy.nodeInfo().legalIdentitiesAndCerts.first().party
    }

    @AfterEach
    fun done() {
        connection.use {
            notary.close()
        }
    }


    @Test
	fun `test attachments`() {
        val attachment = InputStreamAndHash.createInMemoryTestZip(attachmentSize, 1)
        assertFalse(rpcProxy.attachmentExists(attachment.sha256))
        val id = attachment.inputStream.use { rpcProxy.uploadAttachment(it) }
        assertEquals(attachment.sha256, id, "Attachment has incorrect SHA256 hash")

        val hash = HashingInputStream(Hashing.sha256(), rpcProxy.openAttachment(id)).use {
            it.copyTo(NULL_OUTPUT_STREAM)
            SecureHash.createSHA256(it.hash().asBytes())
        }
        assertEquals(attachment.sha256, hash)
    }

    @Disabled("CORDA-1520 - After switching from Kryo to AMQP this test won't work")
    @Test
	fun `test wrapped attachments`() {
        val attachment = InputStreamAndHash.createInMemoryTestZip(attachmentSize, 1)
        assertFalse(rpcProxy.attachmentExists(attachment.sha256))
        val id = WrapperStream(attachment.inputStream).use { rpcProxy.uploadAttachment(it) }
        assertEquals(attachment.sha256, id, "Attachment has incorrect SHA256 hash")

        val hash = HashingInputStream(Hashing.sha256(), rpcProxy.openAttachment(id)).use {
            it.copyTo(NULL_OUTPUT_STREAM)
            SecureHash.createSHA256(it.hash().asBytes())
        }
        assertEquals(attachment.sha256, hash)
    }

    @Test
	fun `test starting flow`() {
        rpcProxy.startFlow(::CashIssueFlow, 127.POUNDS, OpaqueBytes.of(0), notaryNodeIdentity)
                .returnValue.getOrThrow(timeout)
    }

    @Test
	fun `test starting tracked flow`() {
        var trackCount = 0
        val handle = rpcProxy.startTrackedFlow(
                ::CashIssueFlow, 429.DOLLARS, OpaqueBytes.of(0), notaryNodeIdentity
        )
        val updateLatch = CountDownLatch(1)
        handle.progress.subscribe { msg ->
            log.info("Flow>> $msg")
            ++trackCount
            updateLatch.countDown()
        }
        handle.returnValue.getOrThrow(timeout)
        updateLatch.await()
        assertNotEquals(0, trackCount)
    }

    @Test
	fun `test network map`() {
        assertEquals(notaryConfig.legalName, notaryNodeIdentity.name)
    }

    @Test
	fun `test state machines`() {
        val (stateMachines, updates) = rpcProxy.stateMachinesFeed()
        assertEquals(0, stateMachines.size)

        val updateLatch = CountDownLatch(1)
        val updateCount = AtomicInteger(0)
        updates.subscribe { update ->
            if (update is StateMachineUpdate.Added) {
                log.info("StateMachine>> Id=${update.id}")
                updateCount.incrementAndGet()
                updateLatch.countDown()
            }
        }

        // Now issue some cash
        rpcProxy.startFlow(::CashIssueFlow, 513.SWISS_FRANCS, OpaqueBytes.of(0), notaryNodeIdentity)
                .returnValue.getOrThrow(timeout)
        updateLatch.await()
        assertEquals(1, updateCount.get())
    }

    @Test
	fun `test vault track by`() {
        val (vault, vaultUpdates) = rpcProxy.vaultTrackBy<Cash.State>(paging = PageSpecification(DEFAULT_PAGE_NUM))
        assertEquals(0, vault.totalStatesAvailable)

        val updateLatch = CountDownLatch(1)
        vaultUpdates.subscribe { update ->
            log.info("Vault>> FlowId=${update.flowId}")
            updateLatch.countDown()
        }

        // Now issue some cash
        rpcProxy.startFlow(::CashIssueFlow, 629.POUNDS, OpaqueBytes.of(0), notaryNodeIdentity)
                .returnValue.getOrThrow(timeout)
        updateLatch.await()

        // Check that this cash exists in the vault
        val cashBalance = rpcProxy.getCashBalances()
        log.info("Cash Balances: $cashBalance")
        assertEquals(1, cashBalance.size)
        assertEquals(629.POUNDS, cashBalance[Currency.getInstance("GBP")])
    }

    @Test
	fun `test vault query by`() {
        // Now issue some cash
        rpcProxy.startFlow(::CashIssueFlow, 629.POUNDS, OpaqueBytes.of(0), notaryNodeIdentity)
                .returnValue.getOrThrow(timeout)

        val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL)
        val paging = PageSpecification(DEFAULT_PAGE_NUM, 10)
        val sorting = Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))

        val queryResults = rpcProxy.vaultQueryBy<Cash.State>(criteria, paging, sorting)
        assertEquals(1, queryResults.totalStatesAvailable)
        assertEquals(queryResults.states.first().state.data.amount.quantity, 629.POUNDS.quantity)

        rpcProxy.startFlow(::CashPaymentFlow, 100.POUNDS, notaryNodeIdentity, true, notaryNodeIdentity).returnValue.getOrThrow()

        val moreResults = rpcProxy.vaultQueryBy<Cash.State>(criteria, paging, sorting)
        assertEquals(3, moreResults.totalStatesAvailable)   // 629 - 100 + 100

        // Check that this cash exists in the vault
        val cashBalances = rpcProxy.getCashBalances()
        log.info("Cash Balances: $cashBalances")
        assertEquals(1, cashBalances.size)
        assertEquals(629.POUNDS, cashBalances[Currency.getInstance("GBP")])
    }

    @Test
	fun `test cash balances`() {
        val startCash = rpcProxy.getCashBalances()
        println(startCash)
        assertTrue(startCash.isEmpty(), "Should not start with any cash")

        val flowHandle = rpcProxy.startFlow(::CashIssueFlow, 629.DOLLARS, OpaqueBytes.of(0), notaryNodeIdentity)
        println("Started issuing cash, waiting on result")
        flowHandle.returnValue.get()

        val balance = rpcProxy.getCashBalance(USD)
        println("Balance: $balance")
        assertEquals(629.DOLLARS, balance)
    }

    @Test
	fun `test kill flow without killFlow permission`() {
        val flowHandle = rpcProxy.startFlow(::SleepingFlow, 1.minutes)
        notary.connect(nonUser).use { connection ->
            val rpcProxy = connection.proxy
            assertThatExceptionOfType(PermissionException::class.java).isThrownBy {
                rpcProxy.killFlow(flowHandle.id)
            }.withMessageMatching("User not authorized to perform RPC call .*killFlow.*")
        }
    }

    @Test
	fun `test kill flow with killFlow permission`() {
        val flowHandle = rpcProxy.startFlow(::SleepingFlow, 1.minutes)
        notary.connect(rpcUser).use { connection ->
            val rpcProxy = connection.proxy
            assertTrue(rpcProxy.killFlow(flowHandle.id))
        }
    }

    private fun fetchNotaryIdentity(): NodeInfo {
        val nodeInfo = rpcProxy.networkMapSnapshot()
        assertEquals(1, nodeInfo.size)
        return nodeInfo[0]
    }

    // This InputStream cannot have been whitelisted.
    private class WrapperStream(input: InputStream) : FilterInputStream(input)
}
