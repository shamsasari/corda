package net.corda.verifier

import net.corda.core.contracts.Attachment
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.Party
import net.corda.core.internal.SerializedTransactionState
import net.corda.core.internal.verification.VerificationSupport
import net.corda.core.node.NetworkParameters
import java.security.PublicKey

class ExternalVerificationContext(
        override val appClassLoader: ClassLoader,
        private val externalVerifier: ExternalVerifier,
        private val transactionInputsAndReferences: Map<StateRef, SerializedTransactionState>
) : VerificationSupport {
    override fun getParty(key: PublicKey): Party? = externalVerifier.getParty(key)

    override fun getAttachment(id: SecureHash): Attachment? = externalVerifier.getAttachmentResult(id).attachment

    override fun isAttachmentTrusted(attachment: Attachment): Boolean = externalVerifier.getAttachmentResult(attachment.id).isTrusted

    override fun getNetworkParameters(id: SecureHash?): NetworkParameters? = externalVerifier.getNetworkParameters(id)

    override fun getSerializedState(stateRef: StateRef): SerializedTransactionState = transactionInputsAndReferences.getValue(stateRef)

    override fun getTrustedClassAttachment(className: String): Attachment? {
        return externalVerifier.getTrustedClassAttachment(className)
    }

    override fun fixupAttachmentIds(attachmentIds: Collection<SecureHash>): Set<SecureHash> {
        return externalVerifier.fixupAttachmentIds(attachmentIds)
    }
}
