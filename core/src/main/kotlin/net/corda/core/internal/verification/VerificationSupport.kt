package net.corda.core.internal.verification

import net.corda.core.contracts.Attachment
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.Party
import net.corda.core.internal.SerializedStateAndRef
import net.corda.core.node.NetworkParameters
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.internal.AttachmentsClassLoaderCache
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.defaultVerifier
import java.security.PublicKey

/**
 * Represents the operations required to resolve and verify a transaction.
 */
interface VerificationSupport {
    val attachmentsClassLoaderCache: AttachmentsClassLoaderCache? get() = null

    fun getParty(key: PublicKey): Party?

    fun getAttachment(id: SecureHash): Attachment?

    fun isAttachmentTrusted(attachment: Attachment): Boolean

    fun getNetworkParameters(id: SecureHash?): NetworkParameters?

    fun getSerializedState(stateRef: StateRef): SerializedStateAndRef?

    fun getTrustedClassAttachment(className: String): Attachment?

    fun fixupAttachmentIds(attachmentIds: Collection<SecureHash>): Set<SecureHash>

    fun createVerifier(ltx: LedgerTransaction, serializationContext: SerializationContext): Verifier {
        return defaultVerifier(ltx, serializationContext)
    }
}
