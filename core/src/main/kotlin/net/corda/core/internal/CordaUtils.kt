@file:Suppress("TooManyFunctions")
package net.corda.core.internal

import net.corda.core.contracts.ContractClassName
import net.corda.core.contracts.NamedByHash
import net.corda.core.contracts.TransactionResolutionException
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.algorithm
import net.corda.core.flows.DataVendingFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.node.NetworkParameters
import net.corda.core.node.ServiceHub
import net.corda.core.node.ServicesForResolution
import net.corda.core.node.ZoneVersionTooLowException
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.SerializedBytes
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.UntrustworthyData
import org.slf4j.MDC
import java.security.PublicKey

// *Internal* Corda-specific utilities.


// When incrementing platformVersion make sure to update PLATFORM_VERSION in constants.properties as well.
const val PLATFORM_VERSION = 14

fun ServicesForResolution.ensureMinimumPlatformVersion(requiredMinPlatformVersion: Int, feature: String) {
    checkMinimumPlatformVersion(networkParameters.minimumPlatformVersion, requiredMinPlatformVersion, feature)
}

fun checkMinimumPlatformVersion(minimumPlatformVersion: Int, requiredMinPlatformVersion: Int, feature: String) {
    if (minimumPlatformVersion < requiredMinPlatformVersion) {
        throw ZoneVersionTooLowException(
                "$feature requires all nodes on the Corda compatibility zone to be running at least platform version " +
                        "$requiredMinPlatformVersion. The current zone is only enforcing a minimum platform version of " +
                        "$minimumPlatformVersion. Please contact your zone operator."
        )
    }
}

/**
 * The configured instance of DigestService which is passed by default to instances of classes like TransactionBuilder
 * and as a parameter to MerkleTree.getMerkleTree(...) method. Default: SHA2_256.
 */
val ServicesForResolution.digestService get() = HashAgility.digestService

fun ServicesForResolution.requireSupportedHashType(hash: NamedByHash) {
    require(HashAgility.isAlgorithmSupported(hash.id.algorithm)) {
        "Tried to record a transaction with non-standard hash algorithm ${hash.id.algorithm} (experimental mode off)"
    }
}

// JDK11: revisit (JDK 9+ uses different numbering scheme: see https://docs.oracle.com/javase/9/docs/api/java/lang/Runtime.Version.html)
@Throws(NumberFormatException::class)
fun getJavaUpdateVersion(javaVersion: String): Long = javaVersion.substringAfter("_").substringBefore("-").toLong()

enum class JavaVersion(val versionString: String) {
    Java_1_8("1.8"),
    Java_11("11");

    companion object {
        fun isVersionAtLeast(version: JavaVersion): Boolean {
            return currentVersion.toFloat() >= version.versionString.toFloat()
        }

        private val currentVersion: String = System.getProperty("java.specification.version") ?:
                                               throw IllegalStateException("Unable to retrieve system property java.specification.version")
    }
}

/** Provide access to internal method for AttachmentClassLoaderTests. */
fun TransactionBuilder.toWireTransaction(services: ServicesForResolution, serializationContext: SerializationContext): WireTransaction {
    return toWireTransactionWithContext(services, serializationContext)
}

/** Checks if this flow is an idempotent flow. */
fun Class<out FlowLogic<*>>.isIdempotentFlow(): Boolean {
    return IdempotentFlow::class.java.isAssignableFrom(this)
}

/** Check that network parameters hash on this transaction is the current hash for the network. */
fun FlowLogic<*>.checkParameterHash(networkParametersHash: SecureHash?) {
    // Transactions created on Corda 3.x or below do not contain network parameters,
    // so no checking is done until the minimum platform version is at least 4.
    if (networkParametersHash == null) {
        if (serviceHub.networkParameters.minimumPlatformVersion < PlatformVersionSwitches.NETWORK_PARAMETERS_COMPONENT_GROUP) return
        else throw IllegalArgumentException("Transaction for notarisation doesn't contain network parameters hash.")
    } else {
        requireNotNull(serviceHub.networkParametersService.lookup(networkParametersHash)) {
            "Transaction for notarisation contains unknown parameters hash: $networkParametersHash"
        }
    }

    // TODO: [ENT-2666] Implement network parameters fuzzy checking. By design in Corda network we have propagation time delay.
    //       We will never end up in perfect synchronization with all the nodes. However, network parameters update process
    //       lets us predict what is the reasonable time window for changing parameters on most of the nodes.
    //       For now we don't check whether the attached network parameters match the current ones.
}

fun <T : Any> SerializedBytes<Any>.checkPayloadIs(type: Class<T>): UntrustworthyData<T> {
    val payloadData: T = try {
        val serializer = SerializationDefaults.SERIALIZATION_FACTORY
        serializer.deserialize(this, type, SerializationDefaults.P2P_CONTEXT)
    } catch (ex: Exception) {
        throw IllegalArgumentException("Payload invalid", ex)
    }
    return type.castIfPossible(payloadData)?.let { UntrustworthyData(it) }
            ?: throw IllegalArgumentException("We were expecting a ${type.name} but we instead got a ${payloadData.javaClass.name} ($payloadData)")
}

/**
 * Ensures each log entry from the current thread will contain id of the transaction in the MDC.
 */
internal fun SignedTransaction.pushToLoggingContext() {
    MDC.put("tx_id", id.toString())
}

private fun isPackageValid(packageName: String): Boolean {
    return packageName.isNotEmpty() &&
            !packageName.endsWith(".") &&
            packageName.split(".").all { token ->
                Character.isJavaIdentifierStart(token[0]) && token.toCharArray().drop(1).all { Character.isJavaIdentifierPart(it) }
            }
}

/** Check if a string is a legal Java package name. */
fun requirePackageValid(name: String) {
    require(isPackageValid(name)) { "Invalid Java package name: `$name`." }
}

/**
 * This is a wildcard payload to be used by the invoker of the [DataVendingFlow] to allow unlimited access to its vault.
 *
 * TODO Fails with a serialization exception if it is not a list. Why?
 */
@CordaSerializable
object RetrieveAnyTransactionPayload : ArrayList<Any>()

/**
 * Returns true if the [fullClassName] is in a subpackage of [packageName].
 * E.g.: "com.megacorp" owns "com.megacorp.tokens.MegaToken"
 *
 * Note: The ownership check is ignoring case to prevent people from just releasing a jar with: "com.megaCorp.megatoken" and pretend they are MegaCorp.
 * By making the check case insensitive, the node will require that the jar is signed by MegaCorp, so the attack fails.
 */
private fun owns(packageName: String, fullClassName: String): Boolean = fullClassName.startsWith("$packageName.", ignoreCase = true)

/** Returns the public key of the package owner of the [contractClassName], or null if not owned. */
fun NetworkParameters.getPackageOwnerOf(contractClassName: ContractClassName): PublicKey? {
    return packageOwnership.entries.singleOrNull { owns(it.key, contractClassName) }?.value
}

// Make sure that packages don't overlap so that ownership is clear.
fun noPackageOverlap(packages: Collection<String>): Boolean {
    return packages.all { outer -> packages.none { inner -> inner != outer && inner.startsWith("$outer.") } }
}

fun ServiceHub.getRequiredTransaction(txhash: SecureHash): SignedTransaction {
    return validatedTransactions.getTransaction(txhash) ?: throw TransactionResolutionException(txhash)
}
