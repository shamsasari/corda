package net.corda.node.verification

import net.corda.core.internal.AbstractAttachment
import net.corda.core.internal.copyTo
import net.corda.core.internal.div
import net.corda.core.internal.mapToSet
import net.corda.core.internal.readFully
import net.corda.core.serialization.serialize
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.debug
import net.corda.node.services.api.ServiceHubInternal
import net.corda.serialization.internal.GeneratedAttachment
import net.corda.serialization.internal.amqp.AbstractAMQPSerializationScheme.Companion.customSerializers
import net.corda.serialization.internal.amqp.AbstractAMQPSerializationScheme.Companion.serializationWhitelists
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.AttachmentResult
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.Initialisation
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.NetworkParametersResult
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.PartyResult
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.TrustedClassAttachmentResult
import net.corda.serialization.internal.verifier.ExternalVerifierInbound.VerificationRequest
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerificationFailure
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerificationSuccess
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerifierRequest
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerifierRequest.GetAttachment
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerifierRequest.GetNetworkParameters
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerifierRequest.GetParty
import net.corda.serialization.internal.verifier.ExternalVerifierOutbound.VerifierRequest.GetTrustedClassAttachment
import net.corda.serialization.internal.verifier.readCordaSerializable
import net.corda.serialization.internal.verifier.writeCordaSerializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.ProcessBuilder.Redirect
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories

/**
 * Handle to the node's external verifier. The verifier process is started lazily on the first verification request.
 */
class ExternalVerifierHandle(private val serviceHub: ServiceHubInternal) : AutoCloseable {
    companion object {
        private val log = contextLogger()

        private val verifierJar: Path = Files.createTempFile("corda-external-verifier", ".jar")
        init {
            // Extract the embedded verifier jar
            Companion::class.java.getResourceAsStream("external-verifier.jar")!!.use {
                it.copyTo(verifierJar, REPLACE_EXISTING)
            }
            verifierJar.toFile().deleteOnExit()
        }
    }

    private lateinit var server: ServerSocket
    @Volatile
    private var connection: Connection? = null

    fun verifyTransaction(stx: SignedTransaction, checkSufficientSignatures: Boolean) {
        log.info("Verify $stx externally, checkSufficientSignatures=$checkSufficientSignatures")
        // By definition input states are unique, and so it makes sense to eagerly send them across with the transaction.
        // Reference states are not, but for now we'll send them anyway and assume they aren't used often. If this assumption is not
        // correct, and there's a benefit, then we can send them lazily.
        val serializedInputsAndReferences = (stx.inputs + stx.references).associateWith(serviceHub::getSerializedState)

        // To keep things simple the verifier only supports one verification request at a time.
        synchronized(this) {
            val connection = getConnection()
            connection.toVerifier.writeCordaSerializable(VerificationRequest(stx, serializedInputsAndReferences, checkSufficientSignatures))
            // Send the verification request and then wait for any requests from verifier for more information. The last message will either
            // be a verification success or failure message.
            while (true) {
                val message = connection.fromVerifier.readCordaSerializable<ExternalVerifierOutbound>()
                log.debug { "Received from external verifier: $message" }
                when (message) {
                    // Process the information the verifier needs and then loop back and wait for more messages
                    is VerifierRequest -> processVerifierRequest(message, connection)
                    // These two cases break the loop, either by returning on successful verification, or re-throwing the verification error
                    is VerificationSuccess -> return
                    is VerificationFailure -> throw message.throwable
                }
            }
        }
    }

    private fun getConnection(): Connection {
        if (!::server.isInitialized) {
            server = ServerSocket(0)
            // Just in case...
            Runtime.getRuntime().addShutdownHook(Thread(::close))
        }

        return connection ?: Connection().also { connection = it }
    }

    private fun processVerifierRequest(request: VerifierRequest, connection: Connection) {
        val result = when (request) {
            is GetParty -> PartyResult(serviceHub.getParty(request.key))
            is GetAttachment -> {
                val baseAttachment = serviceHub.attachments.openAttachment(request.id)
                val isTrusted = baseAttachment?.let(serviceHub::isAttachmentTrusted) ?: false
                val attachment = when (baseAttachment) {
                    // The Attachment retrieved from the database is not serialisable, just we have to convert into one
                    is AbstractAttachment -> GeneratedAttachment(baseAttachment.open().readFully(), baseAttachment.uploader)
                    // For everything else we keep as is, in particular preserving ContractAttachment
                    else -> baseAttachment
                }
                AttachmentResult(attachment, isTrusted)
            }
            is GetNetworkParameters -> NetworkParametersResult(serviceHub.getNetworkParameters(request.id))
            is GetTrustedClassAttachment -> {
                TrustedClassAttachmentResult(serviceHub.getTrustedClassAttachment(request.className)?.id)
            }
        }
        log.debug { "Sending response to external verifier: $result" }
        connection.toVerifier.writeCordaSerializable(result)
    }

    override fun close() {
        connection?.let {
            connection = null
            try {
                it.close()
            } finally {
                server.close()
            }
        }
    }

    private inner class Connection : AutoCloseable {
        private val verifierProcess: Process
        private val socket: Socket
        val toVerifier: DataOutputStream
        val fromVerifier: DataInputStream

        init {
            val logsDirectory = (serviceHub.configuration.baseDirectory / "logs").createDirectories()
            val command = listOf(
                    "${System.getProperty("java.home") / "bin" / "java"}",
                    "-jar",
                    "$verifierJar",
                    "${server.localPort}",
                    System.getProperty("log4j2.level")?.lowercase() ?: "info"  // TODO
            )
            log.debug { "Verifier command: $command" }
            verifierProcess = ProcessBuilder(command)
                    .redirectOutput(Redirect.appendTo((logsDirectory / "verifier-stdout.log").toFile()))
                    .redirectError(Redirect.appendTo((logsDirectory / "verifier-stderr.log").toFile()))
                    .directory(serviceHub.configuration.baseDirectory.toFile())
                    .start()
            log.info("External verifier process started; PID ${verifierProcess.pid()}")

            verifierProcess.onExit().whenComplete { _, _ ->
                if (connection != null) {
                    log.error("The external verifier has unexpectedly terminated with error code ${verifierProcess.exitValue()}. " +
                            "Please check verifier logs for more details.")
                }
                // Allow a new process to be started on the next verification request
                connection = null
            }

            socket = server.accept()
            toVerifier = DataOutputStream(socket.outputStream)
            fromVerifier = DataInputStream(socket.inputStream)

            val cordapps = serviceHub.cordappProvider.cordapps
            val initialisation = Initialisation(
                    customSerializerClassNames = cordapps.customSerializers.mapToSet { it.javaClass.name },
                    serializationWhitelistClassNames = cordapps.serializationWhitelists.mapToSet { it.javaClass.name },
                    System.getProperty("experimental.corda.customSerializationScheme"), // See Node#initialiseSerialization
                    serializedCurrentNetworkParameters = serviceHub.networkParameters.serialize()
            )
            toVerifier.writeCordaSerializable(initialisation)
        }

        override fun close() {
            try {
                socket.close()
            } finally {
                verifierProcess.destroyForcibly()
            }
        }
    }
}
