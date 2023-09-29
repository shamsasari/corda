package net.corda.coretests.flows

import co.paralleluniverse.fibers.Suspendable
import com.natpryce.hamkrest.*
import com.natpryce.hamkrest.assertion.assertThat
import net.corda.core.contracts.Attachment
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.FetchAttachmentsFlow
import net.corda.core.internal.FetchDataFlow
import net.corda.core.internal.cordapp.CordappImpl.Companion.DEFAULT_CORDAPP_VERSION
import net.corda.core.internal.hash
import net.corda.node.services.persistence.NodeAttachmentService
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.makeUnique
import net.corda.testing.core.singleIdentity
import net.corda.testing.internal.fakeAttachment
import net.corda.coretesting.internal.matchers.flow.willReturn
import net.corda.coretesting.internal.matchers.flow.willThrow
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNodeParameters
import net.corda.testing.node.internal.TestStartedNode
import org.junit.AfterClass
import org.junit.Ignore
import org.junit.Test

@Ignore("TODO JDK17: class cast exception")
class AttachmentTests : WithMockNet {
    companion object {
        val classMockNet = InternalMockNetwork()

        @JvmStatic
        @AfterClass
        fun cleanUp() = classMockNet.stopNodes()
    }

    override val mockNet = classMockNet

    // Test nodes
    private val aliceNode = makeNode(ALICE_NAME)
    private val bobNode = makeNode(BOB_NAME)
    private val alice = aliceNode.info.singleIdentity()

    @Test(timeout=300_000)
	fun `download and store`() {
        // Insert an attachment into node zero's store directly.
        val id = aliceNode.importAttachment(fakeAttachment("file1.txt", "Some useful content"))

        // Get node one to run a flow to fetch it and insert it.
        assertThat(
                bobNode.startAttachmentFlow(id, alice),
                willReturn(noAttachments()))

        // Verify it was inserted into node one's store.
        val attachment = bobNode.getAttachmentWithId(id)
        assertThat(attachment, hashesTo(id))

        // Shut down node zero and ensure node one can still resolve the attachment.
        aliceNode.dispose()

        assertThat(
                bobNode.startAttachmentFlow(id, alice),
                willReturn(soleAttachment(attachment)))
    }

    @Test(timeout=300_000)
	fun missing() {
        val hash: SecureHash = SecureHash.randomSHA256()

        // Get node one to fetch a non-existent attachment.
        assertThat(
                bobNode.startAttachmentFlow(hash, alice),
                willThrow(withRequestedHash(hash)))
    }

    fun withRequestedHash(expected: SecureHash) = has(
            "requested hash",
            FetchDataFlow.HashNotFound::requested,
            equalTo(expected))

    @Test(timeout=300_000)
	fun maliciousResponse() {
        // Make a node that doesn't do sanity checking at load time.
        val badAliceNode = makeBadNode(ALICE_NAME)
        val badAlice = badAliceNode.info.singleIdentity()

        // Insert an attachment into node zero's store directly.
        val attachment = fakeAttachment("file1.txt", "Some useful content")
        val id = badAliceNode.importAttachment(attachment)

        // Corrupt its store.
        val corruptBytes = "arggghhhh".toByteArray()
        System.arraycopy(corruptBytes, 0, attachment, 0, corruptBytes.size)

        val corruptAttachment = NodeAttachmentService.DBAttachment(attId = id.toString(), content = attachment, version = DEFAULT_CORDAPP_VERSION)
        badAliceNode.updateAttachment(corruptAttachment)

        // Get n1 to fetch the attachment. Should receive corrupted bytes.
        assertThat(
                bobNode.startAttachmentFlow(id, badAlice),
                willThrow<FetchDataFlow.DownloadedVsRequestedDataMismatch>()
        )
    }

    @InitiatingFlow
    private class InitiatingFetchAttachmentsFlow(val otherSide: Party, val hashes: Set<SecureHash>) : FlowLogic<FetchDataFlow.Result<Attachment>>() {
        @Suspendable
        override fun call(): FetchDataFlow.Result<Attachment> {
            val session = initiateFlow(otherSide)
            return subFlow(FetchAttachmentsFlow(hashes, session))
        }
    }

    @InitiatedBy(InitiatingFetchAttachmentsFlow::class)
    private class FetchAttachmentsResponse(val otherSideSession: FlowSession) : FlowLogic<Void?>() {
        @Suspendable
        override fun call() = subFlow(TestNoSecurityDataVendingFlow(otherSideSession))
    }

    //region Generators
    override fun makeNode(name: CordaX500Name) =
            mockNet.createPartyNode(makeUnique(name)).apply {
                registerInitiatedFlow(FetchAttachmentsResponse::class.java)
            }

    // Makes a node that doesn't do sanity checking at load time.
    private fun makeBadNode(name: CordaX500Name) = mockNet.createNode(
            InternalMockNodeParameters(legalName = makeUnique(name)),
            nodeFactory = { args ->
                object : InternalMockNetwork.MockNode(args) {
                    override fun start() = super.start().apply { attachments.checkAttachmentsOnLoad = false }
                }
            }).apply { registerInitiatedFlow(FetchAttachmentsResponse::class.java) }

    //endregion

    //region Operations
    private fun TestStartedNode.importAttachment(attachment: ByteArray) =
            attachments.importAttachment(attachment.inputStream(), "test", null)
                    .andRunNetwork()

    private fun TestStartedNode.updateAttachment(attachment: NodeAttachmentService.DBAttachment) = database.transaction {
        session.update(attachment)
    }.andRunNetwork()

    private fun TestStartedNode.startAttachmentFlow(hash: SecureHash, otherSide: Party) = startFlowAndRunNetwork(
            InitiatingFetchAttachmentsFlow(otherSide, setOf(hash)))

    private fun TestStartedNode.getAttachmentWithId(id: SecureHash) =
            attachments.openAttachment(id)!!
    //endregion

    //region Matchers
    private fun noAttachments() = has(FetchDataFlow.Result<Attachment>::fromDisk, isEmpty)

    private fun soleAttachment(attachment: Attachment) = has(FetchDataFlow.Result<Attachment>::fromDisk,
            hasSize(equalTo(1)) and
                    hasElement(attachment))

    private fun hashesTo(hash: SecureHash) = has<Attachment, SecureHash>(
            "hash",
            { it.open().hash() },
            equalTo(hash))
    //endregion

}
