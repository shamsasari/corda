package net.corda.core.internal.verification

import net.corda.core.contracts.Attachment
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.Party
import net.corda.core.internal.SerializedTransactionState
import net.corda.core.node.NetworkParameters
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.internal.AttachmentsClassLoaderCache
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.defaultVerifier
import java.security.PublicKey

/**
 * Represents the operations required to resolve and verify a transaction.
 */
interface VerificationSupport {
    val appClassLoader: ClassLoader

    val attachmentsClassLoaderCache: AttachmentsClassLoaderCache? get() = null

    fun getParty(key: PublicKey): Party?

    fun getAttachment(id: SecureHash): Attachment?

    fun isAttachmentTrusted(attachment: Attachment): Boolean

    fun getNetworkParameters(id: SecureHash?): NetworkParameters?

    fun getSerializedState(stateRef: StateRef): SerializedTransactionState

    fun getTrustedClassAttachment(className: String): Attachment?

    fun fixupAttachmentIds(attachmentIds: Collection<SecureHash>): Set<SecureHash>

    fun getStateAndRef(stateRef: StateRef): StateAndRef<*> = StateAndRef(getSerializedState(stateRef).deserialize(), stateRef)

    fun createVerifier(ltx: LedgerTransaction, serializationContext: SerializationContext): Verifier {
        return defaultVerifier(ltx, serializationContext)
    }
}
