package net.corda.core.transactions

import net.corda.core.CordaException
import net.corda.core.CordaThrowable
import net.corda.core.contracts.*
import net.corda.core.crypto.*
import net.corda.core.identity.Party
import net.corda.core.internal.TransactionDeserialisationException
import net.corda.core.internal.VisibleForTesting
import net.corda.core.internal.services.StateResolutionSupport
import net.corda.core.internal.services.VerificationSupport
import net.corda.core.internal.services.asInternal
import net.corda.core.internal.services.asVerifying
import net.corda.core.node.ServiceHub
import net.corda.core.node.ServicesForResolution
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.internal.MissingSerializerException
import net.corda.core.serialization.serialize
import net.corda.core.utilities.contextLogger
import java.io.NotSerializableException
import java.security.KeyPair
import java.security.PublicKey
import java.security.SignatureException
import java.util.*
import java.util.function.Predicate

/**
 * SignedTransaction wraps a serialized WireTransaction. It contains one or more signatures, each one for
 * a public key (including composite keys) that is mentioned inside a transaction command. SignedTransaction is the top level transaction type
 * and the type most frequently passed around the network and stored. The identity of a transaction is the hash of Merkle root
 * of a WireTransaction, therefore if you are storing data keyed by WT hash be aware that multiple different STs may
 * map to the same key (and they could be different in important ways, like validity!). The signatures on a
 * SignedTransaction might be invalid or missing: the type does not imply validity.
 * A transaction ID should be the hash of the [WireTransaction] Merkle tree root. Thus adding or removing a signature does not change it.
 *
 * @param sigs a list of signatures from individual (non-composite) public keys. This is passed as a list of signatures
 * when verifying composite key signatures, but may be used as individual signatures where a single key is expected to
 * sign.
 */
// DOCSTART 1
@CordaSerializable
data class SignedTransaction(val txBits: SerializedBytes<CoreTransaction>,
                             override val sigs: List<TransactionSignature>
) : TransactionWithSignatures {
    // DOCEND 1
    constructor(ctx: CoreTransaction, sigs: List<TransactionSignature>) : this(ctx.serialize(), sigs) {
        cachedTransaction = ctx
    }

    init {
        require(sigs.isNotEmpty()) { "Tried to instantiate a ${SignedTransaction::class.java.simpleName} without any signatures " }
    }

    /** Cache the deserialized form of the transaction. This is useful when building a transaction or collecting signatures. */
    @Volatile
    @Transient
    private var cachedTransaction: CoreTransaction? = null

    /** The id of the contained [WireTransaction]. */
    override val id: SecureHash get() = coreTransaction.id

    /** Lazily calculated access to the deserialised/hashed transaction data. */
    val coreTransaction: CoreTransaction
        get() = cachedTransaction ?: txBits.deserialize().apply { cachedTransaction = this }

    /** Returns the contained [WireTransaction], or throws if this is a notary change or contract upgrade transaction. */
    val tx: WireTransaction get() = coreTransaction as WireTransaction

    /**
     * Helper function to directly build a [FilteredTransaction] using provided filtering functions,
     * without first accessing the [WireTransaction] [tx].
     */
    fun buildFilteredTransaction(filtering: Predicate<Any>) = tx.buildFilteredTransaction(filtering)

    /** Helper to access the inputs of the contained transaction. */
    val inputs: List<StateRef> get() = coreTransaction.inputs
    /** Helper to access the unspendable inputs of the contained transaction. */
    val references: List<StateRef> get() = coreTransaction.references
    /** Helper to access the notary of the contained transaction. */
    val notary: Party? get() = coreTransaction.notary
    /** Helper to access the network parameters hash for the contained transaction. */
    val networkParametersHash: SecureHash? get() = coreTransaction.networkParametersHash

    override val requiredSigningKeys: Set<PublicKey> get() = tx.requiredSigningKeys

    override fun getKeyDescriptions(keys: Set<PublicKey>): ArrayList<String> {
        // TODO: We need a much better way of structuring this data.
        val descriptions = ArrayList<String>()
        this.tx.commands.forEach { command ->
            if (command.signers.any { it in keys })
                descriptions.add(command.toString())
        }
        if (this.tx.notary?.owningKey in keys)
            descriptions.add("notary")
        return descriptions
    }

    @VisibleForTesting
    fun withAdditionalSignature(keyPair: KeyPair, signatureMetadata: SignatureMetadata): SignedTransaction {
        val signableData = SignableData(tx.id, signatureMetadata)
        return withAdditionalSignature(keyPair.sign(signableData))
    }

    /** Returns the same transaction but with an additional (unchecked) signature. */
    fun withAdditionalSignature(sig: TransactionSignature) = copyWithCache(listOf(sig))

    /** Returns the same transaction but with an additional (unchecked) signatures. */
    fun withAdditionalSignatures(sigList: Iterable<TransactionSignature>) = copyWithCache(sigList)

    /**
     * Creates a copy of the SignedTransaction that includes the provided [sigList]. Also propagates the [cachedTransaction]
     * so the contained transaction does not need to be deserialized again.
     */
    private fun copyWithCache(sigList: Iterable<TransactionSignature>): SignedTransaction {
        val cached = cachedTransaction
        return copy(sigs = sigs + sigList).apply {
            cachedTransaction = cached
        }
    }

    /** Alias for [withAdditionalSignature] to let you use Kotlin operator overloading. */
    operator fun plus(sig: TransactionSignature) = withAdditionalSignature(sig)

    /** Alias for [withAdditionalSignatures] to let you use Kotlin operator overloading. */
    operator fun plus(sigList: Collection<TransactionSignature>) = withAdditionalSignatures(sigList)

    /**
     * Checks the transaction's signatures are valid, optionally calls [verifyRequiredSignatures] to
     * check all required signatures are present, and then calls [WireTransaction.toLedgerTransaction]
     * with the passed in [ServiceHub] to resolve the dependencies, returning an unverified
     * LedgerTransaction.
     *
     * This allows us to perform validation over the entirety of the transaction's contents.
     * WireTransaction only contains StateRef for the inputs and hashes for the attachments,
     * rather than ContractState instances for the inputs and Attachment instances for the attachments.
     *
     * @throws AttachmentResolutionException if a required attachment was not found in storage.
     * @throws TransactionResolutionException if an input points to a transaction not found in storage.
     * @throws SignatureException if any signatures were invalid or unrecognised
     * @throws SignaturesMissingException if any signatures that should have been present are missing.
     */
    @JvmOverloads
    @Throws(SignatureException::class, AttachmentResolutionException::class, TransactionResolutionException::class)
    fun toLedgerTransaction(services: ServiceHub, checkSufficientSignatures: Boolean = true): LedgerTransaction {
        return toLedgerTransactionInternal(services.asVerifying(), checkSufficientSignatures)
    }

    private fun toLedgerTransactionInternal(services: VerificationSupport, checkSufficientSignatures: Boolean): LedgerTransaction {
        // TODO: We could probably optimise the below by
        // a) not throwing if threshold is eventually satisfied, but some of the rest of the signatures are failing.
        // b) omit verifying signatures when threshold requirement is met.
        // c) omit verifying signatures from keys not included in [requiredSigningKeys].
        // For the above to work, [checkSignaturesAreValid] should take the [requiredSigningKeys] as input
        // and probably combine logic from signature validation and key-fulfilment
        // in [TransactionWithSignatures.verifySignaturesExcept].
        if (checkSufficientSignatures) {
            verifyRequiredSignatures() // It internally invokes checkSignaturesAreValid().
        } else {
            checkSignaturesAreValid()
        }
        // We need parameters check here, because finality flow calls stx.toLedgerTransaction() and then verify.
        resolveAndCheckNetworkParameters(services)
        return tx.toLedgerTransactionInternal(services)
    }

    /**
     * Checks the transaction's signatures are valid, optionally calls [verifyRequiredSignatures] to check
     * all required signatures are present. Resolves inputs and attachments from the local storage and performs full
     * transaction verification, including running the contracts.
     *
     * @throws AttachmentResolutionException if a required attachment was not found in storage.
     * @throws TransactionResolutionException if an input points to a transaction not found in storage.
     * @throws SignatureException if any signatures were invalid or unrecognised
     * @throws SignaturesMissingException if any signatures that should have been present are missing.
     */
    @JvmOverloads
    @Throws(SignatureException::class, AttachmentResolutionException::class, TransactionResolutionException::class, TransactionVerificationException::class)
    fun verify(services: ServiceHub, checkSufficientSignatures: Boolean = true) {
        return verifyInternal(services.asVerifying(), checkSufficientSignatures)
    }

    private fun verifyInternal(services: VerificationSupport, checkSufficientSignatures: Boolean) {
        resolveAndCheckNetworkParameters(services)
        when (coreTransaction) {
            is NotaryChangeWireTransaction -> verifyNotaryChangeTransaction(services, checkSufficientSignatures)
            is ContractUpgradeWireTransaction -> verifyContractUpgradeTransaction(services, checkSufficientSignatures)
            else -> verifyRegularTransaction(services, checkSufficientSignatures)
        }
    }

    @Suppress("ThrowsCount")
    private fun resolveAndCheckNetworkParameters(resolutionSupport: StateResolutionSupport) {
        val txNetParams = resolutionSupport.getNetworkParameters(networkParametersHash) ?: throw TransactionResolutionException(id)
        val groupedInputsAndRefs = (inputs + references).groupBy { it.txhash }
        groupedInputsAndRefs.forEach { txId, stateRefs ->
            val inputTxNetParams = resolutionSupport.getSignedTransaction(txId)
                    ?.let { resolutionSupport.getNetworkParameters(it.networkParametersHash) }
                    ?: throw TransactionResolutionException(id)
            if (txNetParams.epoch < inputTxNetParams.epoch)
                throw TransactionVerificationException.TransactionNetworkParameterOrderingException(id, stateRefs[0], txNetParams, inputTxNetParams)
        }
    }

    /** No contract code is run when verifying notary change transactions, it is sufficient to check invariants during initialisation. */
    private fun verifyNotaryChangeTransaction(resolutionSupport: StateResolutionSupport, checkSufficientSignatures: Boolean) {
        val ntx = NotaryChangeLedgerTransaction.resolve(this, resolutionSupport)
        if (checkSufficientSignatures) ntx.verifyRequiredSignatures()
        else checkSignaturesAreValid()
    }

    /** No contract code is run when verifying contract upgrade transactions, it is sufficient to check invariants during initialisation. */
    private fun verifyContractUpgradeTransaction(resolutionSupport: StateResolutionSupport, checkSufficientSignatures: Boolean) {
        val ctx = ContractUpgradeLedgerTransaction.resolve(this, resolutionSupport)
        if (checkSufficientSignatures) ctx.verifyRequiredSignatures()
        else checkSignaturesAreValid()
    }

    // TODO: Verify contract constraints here as well as in LedgerTransaction to ensure that anything being deserialised
    // from the attachment is trusted. This will require some partial serialisation work to not load the ContractState
    // objects from the TransactionState.
    private fun verifyRegularTransaction(services: VerificationSupport, checkSufficientSignatures: Boolean) {
        val ltx = toLedgerTransactionInternal(services, checkSufficientSignatures)
        try {
            services.doVerify(ltx.createVerifier())
        } catch (e: NoClassDefFoundError) {
            checkReverifyAllowed(e)
            val missingClass = e.message ?: throw e
            log.warn("Transaction {} has missing class: {}", ltx.id, missingClass)
            reverifyWithFixups(ltx, services, missingClass)
        } catch (e: NotSerializableException) {
            checkReverifyAllowed(e)
            retryVerification(e, e, ltx, services)
        } catch (e: TransactionDeserialisationException) {
            checkReverifyAllowed(e)
            retryVerification(e.cause, e, ltx, services)
        }
    }

    private fun checkReverifyAllowed(ex: Throwable) {
        // If that transaction was created with and after Corda 4 then just fail.
        // The lenient dependency verification is only supported for Corda 3 transactions.
        // To detect if the transaction was created before Corda 4 we check if the transaction has the NetworkParameters component group.
        if (networkParametersHash != null) {
            log.warn("TRANSACTION VERIFY FAILED - No attempt to auto-repair as TX is Corda 4+")
            throw ex
        }
    }

    @Suppress("ThrowsCount")
    private fun retryVerification(cause: Throwable?, ex: Throwable, ltx: LedgerTransaction, services: VerificationSupport) {
        when (cause) {
            is MissingSerializerException -> {
                log.warn("Missing serializers: typeDescriptor={}, typeNames={}", cause.typeDescriptor ?: "<unknown>", cause.typeNames)
                reverifyWithFixups(ltx, services, null)
            }
            is NotSerializableException -> {
                val underlying = cause.cause
                if (underlying is ClassNotFoundException) {
                    val missingClass = underlying.message?.replace('.', '/') ?: throw ex
                    log.warn("Transaction {} has missing class: {}", ltx.id, missingClass)
                    reverifyWithFixups(ltx, services, missingClass)
                } else {
                    throw ex
                }
            }
            else -> throw ex
        }
    }

    // Transactions created before Corda 4 can be missing dependencies on other CorDapps.
    // This code has detected a missing custom serializer - probably located inside a workflow CorDapp.
    // We need to extract this CorDapp from AttachmentStorage and try verifying this transaction again.
    private fun reverifyWithFixups(ltx: LedgerTransaction, verificationSupport: VerificationSupport, missingClass: String?) {
        log.warn("""Detected that transaction $id does not contain all cordapp dependencies.
                    |This may be the result of a bug in a previous version of Corda.
                    |Attempting to re-verify having applied this node's fix-up rules.
                    |Please check with the originator that this is a valid transaction.""".trimMargin())
        val replacementAttachments = computeReplacementAttachmentsFor(ltx, missingClass, verificationSupport)
        log.warn("Reverifying transaction {} with attachments:{}", ltx.id, replacementAttachments.toPrettyString())
        val verifier = ltx.createVerifier(replacementAttachments.toList())
        verificationSupport.doVerify(verifier)
    }

    private fun computeReplacementAttachmentsFor(ltx: LedgerTransaction,
                                                 missingClass: String?,
                                                 verificationSupport: VerificationSupport): Collection<Attachment> {
        val replacements = verificationSupport.fixupAttachments(ltx.attachments)
        if (!replacements.deepEquals(ltx.attachments)) {
            return replacements
        }

        /*
         * We cannot continue unless we have some idea which
         * class is missing from the attachments.
         */
        if (missingClass == null) {
            throw TransactionVerificationException.BrokenTransactionException(
                    txId = ltx.id,
                    message = "No fix-up rules provided for broken attachments:${replacements.toPrettyString()}"
            )
        }

        /*
         * The Node's fix-up rules have not been able to adjust the transaction's attachments,
         * so resort to the original mechanism of trying to find an attachment that contains
         * the missing class. (Do you feel lucky, Punk?)
         */
        val extraAttachment = requireNotNull(verificationSupport.getTrustedClassAttachment(missingClass)) {
            """Transaction $ltx is incorrectly formed. Most likely it was created during version 3 of Corda
               |when the verification logic was more lenient. Attempted to find local dependency for class: $missingClass,
               |but could not find one.
               |If you wish to verify this transaction, please contact the originator of the transaction and install the
               |provided missing JAR.
               |You can install it using the RPC command: `uploadAttachment` without restarting the node.
               |""".trimMargin()
        }

        return replacements.toMutableSet().apply {
            /*
             * Check our transaction doesn't already contain this extra attachment.
             * It seems unlikely that we would, but better safe than sorry!
             */
            if (!add(extraAttachment)) {
                throw TransactionVerificationException.BrokenTransactionException(
                        txId = ltx.id,
                        message = "Unlinkable class $missingClass inside broken attachments:${replacements.toPrettyString()}"
                )
            }

            log.warn("""Detected that transaction $ltx does not contain all cordapp dependencies.
                    |This may be the result of a bug in a previous version of Corda.
                    |Attempting to verify using the additional trusted dependency: $extraAttachment for class $missingClass.
                    |Please check with the originator that this is a valid transaction.
                    |YOU ARE ONLY SEEING THIS MESSAGE BECAUSE THE CORDAPPS THAT CREATED THIS TRANSACTION ARE BROKEN!
                    |WE HAVE TRIED TO REPAIR THE TRANSACTION AS BEST WE CAN, BUT CANNOT GUARANTEE WE HAVE SUCCEEDED!
                    |PLEASE FIX THE CORDAPPS AND MIGRATE THESE BROKEN TRANSACTIONS AS SOON AS POSSIBLE!
                    |THIS MESSAGE IS **SUPPOSED** TO BE SCARY!!
                    |""".trimMargin()
            )
        }
    }

    /**
     * Resolves the underlying base transaction and then returns it, handling any special case transactions such as
     * [NotaryChangeWireTransaction].
     */
    fun resolveBaseTransaction(servicesForResolution: ServicesForResolution): BaseTransaction {
        return BaseTransaction.resolve(this, servicesForResolution.asInternal())
    }

    /**
     * Resolves the underlying transaction with signatures and then returns it, handling any special case transactions
     * such as [NotaryChangeWireTransaction].
     */
    fun resolveTransactionWithSignatures(services: ServicesForResolution): TransactionWithSignatures {
        return when (coreTransaction) {
            is NotaryChangeWireTransaction -> resolveNotaryChangeTransaction(services)
            is ContractUpgradeWireTransaction -> resolveContractUpgradeTransaction(services)
            is WireTransaction -> this
            is FilteredTransaction -> throw IllegalStateException("Persistence of filtered transactions is not supported.")
            else -> throw IllegalStateException("Unknown transaction type ${coreTransaction::class.qualifiedName}")
        }
    }

    /**
     * If [coreTransaction] is a [NotaryChangeWireTransaction], loads the input states and resolves it to a
     * [NotaryChangeLedgerTransaction] so the signatures can be verified.
     */
    fun resolveNotaryChangeTransaction(services: ServicesForResolution): NotaryChangeLedgerTransaction {
        return NotaryChangeLedgerTransaction.resolve(this, services.asInternal())
    }

    /**
     * If [coreTransaction] is a [NotaryChangeWireTransaction], loads the input states and resolves it to a
     * [NotaryChangeLedgerTransaction] so the signatures can be verified.
     */
    fun resolveNotaryChangeTransaction(services: ServiceHub): NotaryChangeLedgerTransaction {
        return NotaryChangeLedgerTransaction.resolve(this, services.asVerifying())
    }

    /**
     * If [coreTransaction] is a [ContractUpgradeWireTransaction], loads the input states and resolves it to a
     * [ContractUpgradeLedgerTransaction] so the signatures can be verified.
     */
    fun resolveContractUpgradeTransaction(services: ServicesForResolution): ContractUpgradeLedgerTransaction {
        return ContractUpgradeLedgerTransaction.resolve(this, services.asInternal())
    }

    override fun toString(): String = "${javaClass.simpleName}(id=$id)"

    private companion object {
        private fun missingSignatureMsg(missing: Set<PublicKey>, descriptions: List<String>, id: SecureHash): String {
            return "Missing signatures on transaction ${id.prefixChars()} for " +
                    "keys: ${missing.joinToString { it.toStringShort() }}, " +
                    "by signers: ${descriptions.joinToString()} "
        }

        private val log = contextLogger()
        private val SEPARATOR = System.lineSeparator() + "-> "

        private fun Collection<*>.deepEquals(other: Collection<*>): Boolean {
            return size == other.size && containsAll(other) && other.containsAll(this)
        }

        private fun Collection<Attachment>.toPrettyString(): String {
            return joinToString(separator = SEPARATOR, prefix = SEPARATOR, postfix = System.lineSeparator()) { attachment ->
                attachment.id.toString()
            }
        }
    }

    class SignaturesMissingException(val missing: Set<PublicKey>, val descriptions: List<String>, override val id: SecureHash)
        : NamedByHash, SignatureException(missingSignatureMsg(missing, descriptions, id)), CordaThrowable by CordaException(missingSignatureMsg(missing, descriptions, id))

    //region Deprecated
    /** Returns the contained [NotaryChangeWireTransaction], or throws if this is a normal transaction. */
    @Deprecated("No replacement, this should not be used outside of Corda core")
    val notaryChangeTx: NotaryChangeWireTransaction
        get() = coreTransaction as NotaryChangeWireTransaction

    @Deprecated("No replacement, this should not be used outside of Corda core")
    fun isNotaryChangeTransaction() = this.coreTransaction is NotaryChangeWireTransaction
    //endregion
}
