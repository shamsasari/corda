package net.corda.core.internal.verification

import net.corda.core.contracts.Attachment
import net.corda.core.contracts.AttachmentResolutionException
import net.corda.core.contracts.ComponentGroupEnum
import net.corda.core.contracts.ContractAttachment
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionResolutionException
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.Party
import net.corda.core.internal.AttachmentTrustCalculator
import net.corda.core.internal.SerializedStateAndRef
import net.corda.core.internal.SerializedTransactionState
import net.corda.core.internal.TRUSTED_UPLOADERS
import net.corda.core.internal.cordapp.CordappProviderInternal
import net.corda.core.internal.getRequiredTransaction
import net.corda.core.node.NetworkParameters
import net.corda.core.node.ServiceHub
import net.corda.core.node.ServicesForResolution
import net.corda.core.node.services.vault.AttachmentQueryCriteria
import net.corda.core.node.services.vault.AttachmentSort
import net.corda.core.node.services.vault.AttachmentSort.AttachmentSortAttribute
import net.corda.core.node.services.vault.Builder
import net.corda.core.node.services.vault.Sort
import net.corda.core.transactions.ContractUpgradeWireTransaction
import net.corda.core.transactions.CoreTransaction
import net.corda.core.transactions.NotaryChangeWireTransaction
import net.corda.core.transactions.WireTransaction
import java.security.PublicKey
import java.util.jar.JarInputStream

interface VerifyingServiceHub : ServiceHub, VerificationSupport {
    override val cordappProvider: CordappProviderInternal

    val attachmentTrustCalculator: AttachmentTrustCalculator

    override fun loadContractAttachment(stateRef: StateRef): Attachment {
        // We may need to recursively chase transactions if there are notary changes.
        return loadContractAttachment(stateRef, null)
    }

    private fun loadContractAttachment(stateRef: StateRef, forContractClassName: String?): Attachment {
        val stx = validatedTransactions.getTransaction(stateRef.txhash) ?: throw TransactionResolutionException(stateRef.txhash)
        return when (val ctx = stx.coreTransaction) {
            is WireTransaction -> {
                val contractClassName = forContractClassName ?: ctx.outRef<ContractState>(stateRef.index).state.contract
                ctx.attachments
                        .asSequence()
                        .mapNotNull { id -> loadAttachmentContainingContract(id, contractClassName) }
                        .firstOrNull() ?: throw AttachmentResolutionException(stateRef.txhash)
            }
            is ContractUpgradeWireTransaction -> {
                attachments.openAttachment(ctx.upgradedContractAttachmentId) ?: throw AttachmentResolutionException(stateRef.txhash)
            }
            is NotaryChangeWireTransaction -> {
                val transactionState = getSerializedState(stateRef)!!.toStateAndRef().state
                val input = ctx.inputs.firstOrNull() ?: throw AttachmentResolutionException(stateRef.txhash)
                // TODO: check only one (or until one is resolved successfully), max recursive invocations check?
                loadContractAttachment(input, transactionState.contract)
            }
            else -> throw UnsupportedOperationException("Attempting to resolve attachment for index ${stateRef.index} of a " +
                    "${ctx.javaClass} transaction. This is not supported.")
        }
    }

    private fun loadAttachmentContainingContract(id: SecureHash, contractClassName: String): Attachment? {
        return attachments.openAttachment(id)?.takeIf { it is ContractAttachment && contractClassName in it.allContracts }
    }

    override fun getParty(key: PublicKey): Party? = identityService.partyFromKey(key)

    override fun getAttachment(id: SecureHash): Attachment? = attachments.openAttachment(id)

    override fun getNetworkParameters(id: SecureHash?): NetworkParameters? {
        return networkParametersService.lookup(id ?: networkParametersService.defaultHash)
    }

    /**
     * This is the main logic that knows how to retrieve the binary representation of [StateRef]s.
     *
     * For [ContractUpgradeWireTransaction] or [NotaryChangeWireTransaction] it knows how to recreate the output state in the
     * correct classloader independent of the node's classpath.
     */
    override fun getSerializedState(stateRef: StateRef): SerializedStateAndRef? {
        val coreTransaction = getRequiredTransaction(stateRef.txhash).coreTransaction
        @Suppress("UNCHECKED_CAST")
        val serialized = when (coreTransaction) {
            is WireTransaction -> coreTransaction.componentGroups
                    .firstOrNull { it.groupIndex == ComponentGroupEnum.OUTPUTS_GROUP.ordinal }
                    ?.components
                    ?.get(stateRef.index) as SerializedTransactionState?
            is ContractUpgradeWireTransaction -> coreTransaction.resolveOutput(stateRef.index, this)
            is NotaryChangeWireTransaction -> coreTransaction.resolveOutput(stateRef, this)
            else -> throw UnsupportedOperationException("Attempting to resolve input ${stateRef.index} of a ${coreTransaction.javaClass} " +
                    "transaction. This is not supported.")
        }
        return serialized?.let { SerializedStateAndRef(it, stateRef) }
    }

    /**
     * Scans trusted (installed locally) attachments to find all that contain the [className].
     * This is required as a workaround until explicit cordapp dependencies are implemented.
     *
     * @return the attachments with the highest version.
     */
    // TODO Should throw when the class is found in multiple contract attachments (not different versions).
    override fun getTrustedClassAttachment(className: String): Attachment? {
        val allTrusted = attachments.queryAttachments(
                AttachmentQueryCriteria.AttachmentsQueryCriteria().withUploader(Builder.`in`(TRUSTED_UPLOADERS)),
                AttachmentSort(listOf(AttachmentSort.AttachmentSortColumn(AttachmentSortAttribute.VERSION, Sort.Direction.DESC)))
        )

        // TODO - add caching if performance is affected.
        for (attId in allTrusted) {
            val attch = attachments.openAttachment(attId)!!
            if (attch.openAsJAR().use { hasFile(it, "$className.class") }) return attch
        }
        return null
    }

    private fun hasFile(jarStream: JarInputStream, className: String): Boolean {
        while (true) {
            val e = jarStream.nextJarEntry ?: return false
            if (e.name == className) {
                return true
            }
        }
    }

    override fun isAttachmentTrusted(attachment: Attachment): Boolean = attachmentTrustCalculator.calculate(attachment)

    override fun fixupAttachmentIds(attachmentIds: Collection<SecureHash>): Set<SecureHash> {
        return cordappProvider.fixupAttachmentIds(attachmentIds)
    }

    /**
     * Try to verify the given transaction on the external verifier, assuming it is available. It is not required to verify externally even
     * if the verifier is available.
     *
     * The default implementation is to only do internal verification.
     *
     * @return true if the transaction should (also) be verified internally, regardless of whether it was verified externally.
     */
    fun tryExternalVerification(transaction: CoreTransaction): Boolean {
        return true
    }
}

fun ServicesForResolution.toVerifyingServiceHub(): VerifyingServiceHub {
    if (this is VerifyingServiceHub) {
        return this
    }
    // All ServicesForResolution instances should also implement VerifyingServiceHub. The only exception is MockServices which does not
    // since it's public API and VerifyingServiceHub is internal. Instead, MockServices has a VerifyingServiceHub "view" which we get at via
    // reflection.
    var clazz: Class<*> = javaClass
    while (true) {
        if (clazz.name == "net.corda.testing.node.MockServices") {
            return clazz.getDeclaredMethod("getVerifyingView").apply { isAccessible = true }.invoke(this) as VerifyingServiceHub
        }
        clazz = clazz.superclass ?: throw ClassCastException("${javaClass.name} is not a VerifyingServiceHub")
    }
}
