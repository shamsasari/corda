package net.corda.verifier

import com.github.benmanes.caffeine.cache.Cache
import net.corda.core.contracts.Attachment
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.Party
import net.corda.core.internal.loadClassOfType
import net.corda.core.internal.mapToSet
import net.corda.core.internal.objectOrNewInstance
import net.corda.core.internal.toSynchronised
import net.corda.core.internal.toTypedArray
import net.corda.core.internal.verification.AttachmentFixups
import net.corda.core.node.NetworkParameters
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.internal.AttachmentsClassLoaderCache
import net.corda.core.serialization.internal.AttachmentsClassLoaderCacheImpl
import net.corda.core.serialization.internal.SerializationEnvironment
import net.corda.core.serialization.internal._contextSerializationEnv
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.debug
import net.corda.serialization.internal.AMQP_P2P_CONTEXT
import net.corda.serialization.internal.CordaSerializationMagic
import net.corda.serialization.internal.SerializationFactoryImpl
import net.corda.serialization.internal.amqp.AbstractAMQPSerializationScheme
import net.corda.serialization.internal.amqp.AccessOrderLinkedHashMap
import net.corda.serialization.internal.amqp.SerializationFactoryCacheKey
import net.corda.serialization.internal.amqp.SerializerFactory
import net.corda.serialization.internal.amqp.amqpMagic
import net.corda.serialization.internal.verifier.AttachmentWithTrust
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.AttachmentResult
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.AttachmentsResult
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.Initialisation
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.NetworkParametersResult
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.PartiesResult
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.TrustedClassAttachmentResult
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.VerificationRequest
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerificationResult
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerifierRequest.GetAttachment
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerifierRequest.GetAttachments
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerifierRequest.GetNetworkParameters
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerifierRequest.GetParties
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerifierRequest.GetTrustedClassAttachment
import net.corda.serialization.internal.verifier.loadCustomSerializationScheme
import net.corda.serialization.internal.verifier.readCordaSerializable
import net.corda.serialization.internal.verifier.writeCordaSerializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import java.security.PublicKey
import java.util.Optional
import net.corda.core.internal.div
import net.corda.core.internal.list

@Suppress("TooGenericExceptionCaught", "MagicNumber")
class ExternalVerifier(
        private val baseDirectory: Path,
        private val fromNode: DataInputStream,
        private val toNode: DataOutputStream
) {
    companion object {
        private val log = contextLogger()
    }

    private val attachmentsClassLoaderCache: AttachmentsClassLoaderCache
    private val attachmentFixups = AttachmentFixups()
    private val parties: OptionalCache<PublicKey, Party>
    private val attachments: OptionalCache<SecureHash, AttachmentWithTrust>
    private val networkParametersMap: OptionalCache<SecureHash, NetworkParameters>
    private val trustedClassAttachments: OptionalCache<String, SecureHash>

    private lateinit var appClassLoader: ClassLoader
    private lateinit var currentNetworkParameters: NetworkParameters

    init {
        val cacheFactory = ExternalVerifierNamedCacheFactory()
        attachmentsClassLoaderCache = AttachmentsClassLoaderCacheImpl(cacheFactory)
        parties = cacheFactory.buildNamed("ExternalVerifier_parties")
        attachments = cacheFactory.buildNamed("ExternalVerifier_attachments")
        networkParametersMap = cacheFactory.buildNamed("ExternalVerifier_networkParameters")
        trustedClassAttachments = cacheFactory.buildNamed("ExternalVerifier_trustedClassAttachments")
    }

    fun run() {
        initialise()
        while (true) {
            val request = fromNode.readCordaSerializable<VerificationRequest>()
            log.debug { "Received $request" }
            verifyTransaction(request)
        }
    }

    private fun initialise() {
        // Use a preliminary serialization context to receive the initialisation message
        _contextSerializationEnv.set(SerializationEnvironment.with(
                verifierSerializationFactory(),
                p2pContext = AMQP_P2P_CONTEXT
        ))

        log.info("Waiting for initialisation message from node...")
        val initialisation = fromNode.readCordaSerializable<Initialisation>()
        log.info("Received $initialisation")

        appClassLoader = createAppClassLoader()

        // Then use the initialisation message to create the correct serialization context
        _contextSerializationEnv.set(null)
        _contextSerializationEnv.set(SerializationEnvironment.with(
                verifierSerializationFactory(initialisation, appClassLoader).apply {
                    initialisation.customSerializationSchemeClassName?.let {
                        registerScheme(loadCustomSerializationScheme(it, appClassLoader))
                    }
                },
                p2pContext = AMQP_P2P_CONTEXT.withClassLoader(appClassLoader)
        ))

        attachmentFixups.load(appClassLoader)

        currentNetworkParameters = initialisation.currentNetworkParameters
        networkParametersMap.put(initialisation.serializedCurrentNetworkParameters.hash, Optional.of(currentNetworkParameters))

        log.info("External verifier initialised")
    }

    private fun createAppClassLoader(): ClassLoader {
        val cordappJarUrls = (baseDirectory / "cordapps").list()
                .stream()
                .filter { it.toString().endsWith(".jar") }
                .map { it.toUri().toURL() }
                .toTypedArray()
        log.debug { "CorDapps: ${cordappJarUrls?.joinToString()}" }
        return URLClassLoader(cordappJarUrls, javaClass.classLoader)
    }

    private fun verifyTransaction(request: VerificationRequest) {
        val verificationContext = ExternalVerificationContext(appClassLoader, attachmentsClassLoaderCache, this, request.stxInputsAndReferences)
        val result = try {
            request.stx.verifyInternal(verificationContext, request.checkSufficientSignatures)
            log.info("${request.stx} verified")
            VerificationResult.Success
        } catch (t: Throwable) {
            log.info("${request.stx} failed to verify", t)
            VerificationResult.Failure(t)
        }
        toNode.writeCordaSerializable(result)
    }

    fun getParties(keys: Collection<PublicKey>): List<Party?> {
        return parties.retrieveAll(keys) {
            request<PartiesResult>(GetParties(it)).parties
        }
    }

    fun getAttachment(id: SecureHash): AttachmentWithTrust? {
        return attachments.retrieve(id) {
            request<AttachmentResult>(GetAttachment(id)).attachment
        }
    }

    fun getAttachments(ids: Collection<SecureHash>): List<AttachmentWithTrust?> {
        return attachments.retrieveAll(ids) {
            request<AttachmentsResult>(GetAttachments(it)).attachments
        }
    }

    fun getTrustedClassAttachment(className: String): Attachment? {
        val attachmentId = trustedClassAttachments.retrieve(className) {
            // GetTrustedClassAttachment returns back the attachment ID, not the whole attachment. This lets us avoid downloading the whole
            // attachment again if we already have it.
            request<TrustedClassAttachmentResult>(GetTrustedClassAttachment(className)).id
        }
        return attachmentId?.let(::getAttachment)?.attachment
    }

    fun getNetworkParameters(id: SecureHash?): NetworkParameters? {
        return if (id == null) {
            currentNetworkParameters
        } else {
            networkParametersMap.retrieve(id) {
                request<NetworkParametersResult>(GetNetworkParameters(id)).networkParameters
            }
        }
    }

    fun fixupAttachmentIds(attachmentIds: Collection<SecureHash>): Set<SecureHash> = attachmentFixups.fixupAttachmentIds(attachmentIds)

    private inline fun <reified T : Any> request(request: Any): T {
        log.debug { "Sending request to node: $request" }
        toNode.writeCordaSerializable(request)
        val response = fromNode.readCordaSerializable<T>()
        log.debug { "Received response from node: $response" }
        return response
    }

    private fun verifierSerializationFactory(initialisation: Initialisation? = null, classLoader: ClassLoader? = null): SerializationFactoryImpl {
        val serializationFactory = SerializationFactoryImpl()
        serializationFactory.registerScheme(AMQPVerifierSerializationScheme(initialisation, classLoader))
        return serializationFactory
    }


    private class AMQPVerifierSerializationScheme(initialisation: Initialisation?, classLoader: ClassLoader?) : AbstractAMQPSerializationScheme(
            initialisation?.customSerializerClassNames.load(classLoader),
            initialisation?.serializationWhitelistClassNames.load(classLoader),
            AccessOrderLinkedHashMap<SerializationFactoryCacheKey, SerializerFactory>(128).toSynchronised()
    ) {
        override fun canDeserializeVersion(magic: CordaSerializationMagic, target: SerializationContext.UseCase): Boolean {
            return magic == amqpMagic && target == SerializationContext.UseCase.P2P
        }

        override fun rpcClientSerializerFactory(context: SerializationContext) = throw UnsupportedOperationException()
        override fun rpcServerSerializerFactory(context: SerializationContext) = throw UnsupportedOperationException()

        companion object {
            inline fun <reified T : Any> Set<String>?.load(classLoader: ClassLoader?): Set<T> {
                return this?.mapToSet { loadClassOfType<T>(it, classLoader = classLoader).kotlin.objectOrNewInstance() } ?: emptySet()
            }
        }
    }
}

private typealias OptionalCache<K, V> = Cache<K, Optional<V>>

private fun <K : Any, V : Any> OptionalCache<K, V>.retrieve(key: K, request: () -> V?): V? {
    return get(key) { Optional.ofNullable(request()) }!!.orElse(null)
}

@Suppress("UNCHECKED_CAST")
private fun <K : Any, V : Any> OptionalCache<K, V>.retrieveAll(keys: Collection<K>, request: (Set<K>) -> List<V?>): List<V?> {
    val optionalResults = getAll(keys) {
        val missingKeys = if (it is Set<*>) it as Set<K> else it.toSet()
        val response = request(missingKeys)
        missingKeys.zip(response) { key, value -> key to Optional.ofNullable(value) }.toMap()
    }
    return keys.map { optionalResults.getValue(it).orElse(null) }
}
