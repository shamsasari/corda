package net.corda.node.services.statemachine.transitions

import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowInfo
import net.corda.core.flows.FlowSession
import net.corda.core.flows.UnexpectedFlowEndException
import net.corda.core.identity.Party
import net.corda.core.internal.DeclaredField
import net.corda.core.internal.FlowIORequest
import net.corda.core.serialization.SerializedBytes
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.toNonEmptySet
import net.corda.node.services.messaging.MessageIdentifier
import net.corda.node.services.messaging.SenderDeduplicationInfo
import net.corda.node.services.messaging.generateShardId
import net.corda.node.services.statemachine.*
import org.slf4j.Logger
import kotlin.collections.LinkedHashMap

/**
 * This transition describes what should happen with a specific [FlowIORequest]. Note that at this time the request
 * is persisted (unless checkpoint was skipped) and the user-space DB transaction is commited.
 *
 * Before this transition we either did a checkpoint or the checkpoint was restored from the database.
 */
class StartedFlowTransition(
        override val context: TransitionContext,
        override val startingState: StateMachineState,
        val started: FlowState.Started
) : Transition {

    companion object {
        private val logger: Logger = contextLogger()
    }

    override fun transition(): TransitionResult {
        val flowIORequest = started.flowIORequest
        val (newState, errorsToThrow) = collectRelevantErrorsToThrow(startingState, flowIORequest)
        if (errorsToThrow.isNotEmpty()) {
            return TransitionResult(
                    newState = newState.copy(isFlowResumed = true),
                    // throw the first exception. TODO should this aggregate all of them somehow?
                    actions = listOf(Action.CreateTransaction),
                    continuation = FlowContinuation.Throw(errorsToThrow[0])
            )
        }
        return when (flowIORequest) {
            is FlowIORequest.Send -> sendTransition(flowIORequest)
            is FlowIORequest.Receive -> receiveTransition(flowIORequest)
            is FlowIORequest.SendAndReceive -> sendAndReceiveTransition(flowIORequest)
            is FlowIORequest.CloseSessions -> closeSessionTransition(flowIORequest)
            is FlowIORequest.WaitForLedgerCommit -> waitForLedgerCommitTransition(flowIORequest)
            is FlowIORequest.Sleep -> sleepTransition(flowIORequest)
            is FlowIORequest.GetFlowInfo -> getFlowInfoTransition(flowIORequest)
            is FlowIORequest.WaitForSessionConfirmations -> waitForSessionConfirmationsTransition()
            is FlowIORequest.ExecuteAsyncOperation<*> -> executeAsyncOperation(flowIORequest)
            FlowIORequest.ForceCheckpoint -> executeForceCheckpoint()
        }.let { terminateSessionsIfRequired(it) }
    }

    private fun waitForSessionConfirmationsTransition(): TransitionResult {
        return builder {
            if (currentState.checkpoint.checkpointState.sessions.values.any { it is SessionState.Initiating }) {
                FlowContinuation.ProcessEvents
            } else {
                resumeFlowLogic(Unit)
            }
        }
    }

    private fun getFlowInfoTransition(flowIORequest: FlowIORequest.GetFlowInfo): TransitionResult {
        val sessionIdToSession = LinkedHashMap<SessionId, FlowSessionImpl>()
        for (session in flowIORequest.sessions) {
            sessionIdToSession[(session as FlowSessionImpl).sourceSessionId] = session
        }
        return builder {
            // Initialise uninitialised sessions in order to receive the associated FlowInfo. Some or all sessions may
            // not be initialised yet.
            sendInitialSessionMessagesIfNeeded(sessionIdToSession.keys)
            val flowInfoMap = getFlowInfoFromSessions(sessionIdToSession)
            if (flowInfoMap == null) {
                FlowContinuation.ProcessEvents
            } else {
                resumeFlowLogic(flowInfoMap)
            }
        }
    }

    private fun TransitionBuilder.getFlowInfoFromSessions(sessionIdToSession: Map<SessionId, FlowSessionImpl>): Map<FlowSession, FlowInfo>? {
        val checkpoint = currentState.checkpoint
        val resultMap = LinkedHashMap<FlowSession, FlowInfo>()
        for ((sessionId, session) in sessionIdToSession) {
            val sessionState = checkpoint.checkpointState.sessions[sessionId]
            if (sessionState is SessionState.Initiated) {
                resultMap[session] = sessionState.peerFlowInfo
            } else {
                return null
            }
        }
        return resultMap
    }

    private fun sleepTransition(flowIORequest: FlowIORequest.Sleep): TransitionResult {
        // This ensures that the [Sleep] request is not executed multiple times if extra
        // [DoRemainingWork] events are pushed onto the fiber's event queue before the flow has really woken up
        return if (!startingState.isWaitingForFuture) {
            builder {
                currentState = currentState.copy(isWaitingForFuture = true)
                actions.add(Action.SleepUntil(currentState, flowIORequest.wakeUpAfter))
                FlowContinuation.ProcessEvents
            }
        } else {
            TransitionResult(startingState)
        }
    }

    private fun waitForLedgerCommitTransition(flowIORequest: FlowIORequest.WaitForLedgerCommit): TransitionResult {
        // This ensures that the [WaitForLedgerCommit] request is not executed multiple times if extra
        // [DoRemainingWork] events are pushed onto the fiber's event queue before the flow has really woken up
        return if (!startingState.isWaitingForFuture) {
            val state = startingState.copy(isWaitingForFuture = true)
            TransitionResult(
                newState = state,
                actions = listOf(
                    Action.CreateTransaction,
                    Action.TrackTransaction(flowIORequest.hash, state),
                    Action.CommitTransaction(state)
                )
            )
        } else {
            TransitionResult(startingState)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun sendAndReceiveTransition(flowIORequest: FlowIORequest.SendAndReceive): TransitionResult {
        val sessionIdToMessage = LinkedHashMap<SessionId, SerializedBytes<Any>>()
        val sessionIdToSession = LinkedHashMap<SessionId, FlowSessionImpl>()
        for ((session, message) in flowIORequest.sessionToMessage) {
            val sessionId = (session as FlowSessionImpl).sourceSessionId
            sessionIdToMessage[sessionId] = message
            sessionIdToSession[sessionId] = session
        }
        return builder {
            sendToSessionsTransition(sessionIdToMessage)
            if (isErrored()) {
                FlowContinuation.ProcessEvents
            } else {
                try {
                    val receivedMap = receiveFromSessionsTransition(sessionIdToSession)
                    if (receivedMap == null) {
                        // We don't yet have the messages, change the suspension to be on Receive
                        val newIoRequest = FlowIORequest.Receive(flowIORequest.sessionToMessage.keys.toNonEmptySet())
                        currentState = currentState.copy(
                            checkpoint = currentState.checkpoint.copy(
                                flowState = FlowState.Started(newIoRequest, started.frozenFiber)
                            )
                        )
                        FlowContinuation.ProcessEvents
                    } else {
                        resumeFlowLogic(receivedMap)
                    }
                } catch (t: Throwable) {
                    // E.g. A session end message received while expecting a data session message
                    resumeFlowLogic(t)
                }
            }
        }
    }

    private fun closeSessionTransition(flowIORequest: FlowIORequest.CloseSessions): TransitionResult {
        return builder {
            val sessionIdsToRemove = flowIORequest.sessions.map { sessionToSessionId(it) }.toSet()
            val existingSessionsToRemove = currentState.checkpoint.checkpointState.sessions.filter { (sessionId, _) ->
                sessionIdsToRemove.contains(sessionId)
            }
            val alreadyClosedSessions = sessionIdsToRemove.filter { sessionId -> sessionId !in existingSessionsToRemove }
            if (alreadyClosedSessions.isNotEmpty()) {
                logger.warn("Attempting to close already closed sessions: $alreadyClosedSessions")
            }

            if (existingSessionsToRemove.isNotEmpty()) {
                val sendEndMessageActions = existingSessionsToRemove.map { (_, sessionState) ->
                    val sinkSessionId = (sessionState as SessionState.Initiated).peerSinkSessionId
                    val message = ExistingSessionMessage(sinkSessionId, EndSessionMessage)
                    val messageType = MessageType.inferFromMessage(message)
                    val messageIdentifier = MessageIdentifier(messageType, generateShardId(context.id.toString()), sinkSessionId, sessionState.nextSendingSeqNumber, currentState.checkpoint.checkpointState.suspensionTime)
                    Action.SendExisting(sessionState.peerParty, message, SenderDeduplicationInfo(messageIdentifier, currentState.senderUUID))
                }
                val signalSessionsEndMap = existingSessionsToRemove.map { (sessionId, _) ->
                    val sessionState = currentState.checkpoint.checkpointState.sessions[sessionId]!!
                    sessionId to Pair(sessionState.lastSenderUUID, sessionState.lastSenderSeqNo)
                }.toMap()

                currentState = currentState.copy(checkpoint = currentState.checkpoint.removeSessions(existingSessionsToRemove.keys), closedSessionsPendingToBeSignalled = currentState.closedSessionsPendingToBeSignalled + signalSessionsEndMap)
                actions.add(Action.RemoveSessionBindings(sessionIdsToRemove))
                actions.add(Action.SendMultiple(emptyList(), sendEndMessageActions))
            }

            resumeFlowLogic(Unit)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun receiveTransition(flowIORequest: FlowIORequest.Receive): TransitionResult {
        return builder {
            val sessionIdToSession = LinkedHashMap<SessionId, FlowSessionImpl>()
            for (session in flowIORequest.sessions) {
                sessionIdToSession[(session as FlowSessionImpl).sourceSessionId] = session
            }
            // send initialises to uninitialised sessions
            sendInitialSessionMessagesIfNeeded(sessionIdToSession.keys)
            try {
                val receivedMap = receiveFromSessionsTransition(sessionIdToSession)
                if (receivedMap == null) {
                    FlowContinuation.ProcessEvents
                } else {
                    resumeFlowLogic(receivedMap)
                }
            } catch (t: Throwable) {
                // E.g. A session end message received while expecting a data session message
                resumeFlowLogic(t)
            }
        }
    }

    private fun TransitionBuilder.receiveFromSessionsTransition(
            sourceSessionIdToSessionMap: Map<SessionId, FlowSessionImpl>
    ): Map<FlowSession, SerializedBytes<Any>>? {
        val checkpoint = currentState.checkpoint
        val pollResult = pollSessionMessages(checkpoint.checkpointState.sessions, sourceSessionIdToSessionMap.keys) ?: return null
        val resultMap = LinkedHashMap<FlowSession, SerializedBytes<Any>>()
        for ((sessionId, message) in pollResult.messages) {
            val session = sourceSessionIdToSessionMap[sessionId]!!
            resultMap[session] = message
        }
        currentState = currentState.copy(
                checkpoint = checkpoint.setSessions(sessions = pollResult.newSessionMap)
        )
        return resultMap
    }

    data class PollResult(
            val messages: Map<SessionId, SerializedBytes<Any>>,
            val newSessionMap: SessionMap
    )

    @Suppress("ComplexMethod", "NestedBlockDepth")
    private fun pollSessionMessages(sessions: SessionMap, sessionIds: Set<SessionId>): PollResult? {
        val newSessionStates = LinkedHashMap(sessions)
        val resultMessages = LinkedHashMap<SessionId, SerializedBytes<Any>>()
        var someNotFound = false
        for (sessionId in sessionIds) {
            val sessionState = sessions[sessionId]
            when (sessionState) {
                is SessionState.Initiated -> {
                    if (!sessionState.hasNextMessageArrived()) {
                        someNotFound = true
                    } else {
                        val (message, newState) = sessionState.extractMessage()
                        newSessionStates[sessionId] = newState
                        // at this point, we've already checked for errors and session ends, so it's guaranteed that the first message will be a data message.
                        resultMessages[sessionId] = if (message is EndSessionMessage) {
                            throw UnexpectedFlowEndException("Received session end message instead of a data session message. Mismatched send and receive?")
                        } else {
                            (message as DataSessionMessage).payload
                        }
                    }
                }
                else -> {
                    someNotFound = true
                }
            }
        }
        return if (someNotFound) {
            return null
        } else {
            PollResult(resultMessages, newSessionStates)
        }
    }

    private fun TransitionBuilder.sendInitialSessionMessagesIfNeeded(sourceSessions: Set<SessionId>) {
        val checkpoint = startingState.checkpoint
        val newSessions = LinkedHashMap<SessionId, SessionState>(checkpoint.checkpointState.sessions)
        for (sourceSessionId in sourceSessions) {
            val sessionState = checkpoint.checkpointState.sessions[sourceSessionId]
            if (sessionState == null) {
                return freshErrorTransition(CannotFindSessionException(sourceSessionId))
            }
            if (sessionState !is SessionState.Uninitiated) {
                continue
            }
            val shardId = generateShardId(context.id.toString())
            val counterpartySessionId = sourceSessionId.calculateInitiatedSessionId()
            val initialMessage = createInitialSessionMessage(sessionState.initiatingSubFlow, sourceSessionId, sessionState.additionalEntropy, null)
            val newSessionState = SessionState.Initiating(
                    bufferedMessages = emptyList(),
                    rejectionError = null,
                    deduplicationSeed = sessionState.deduplicationSeed,
                    nextSendingSeqNumber = 1,
                    shardId = shardId,
                    receivedMessages = emptyMap(),
                    lastSenderUUID = null,
                    lastSenderSeqNo = null
            )
            val messageType = MessageType.inferFromMessage(initialMessage)
            val messageIdentifier = MessageIdentifier(messageType, shardId, counterpartySessionId, 0, checkpoint.checkpointState.suspensionTime)
            actions.add(Action.SendInitial(sessionState.destination, initialMessage, SenderDeduplicationInfo(messageIdentifier, startingState.senderUUID)))
            newSessions[sourceSessionId] = newSessionState
        }
        currentState = currentState.copy(checkpoint = checkpoint.setSessions(sessions = newSessions))
    }

    private fun sendTransition(flowIORequest: FlowIORequest.Send): TransitionResult {
        return builder {
            val sessionIdToMessage = flowIORequest.sessionToMessage.mapKeys {
                sessionToSessionId(it.key)
            }
            sendToSessionsTransition(sessionIdToMessage)
            if (isErrored()) {
                FlowContinuation.ProcessEvents
            } else {
                resumeFlowLogic(Unit)
            }
        }
    }

    private fun TransitionBuilder.sendToSessionsTransition(sourceSessionIdToMessage: Map<SessionId, SerializedBytes<Any>>) {
        val checkpoint = startingState.checkpoint
        val newSessions = LinkedHashMap(checkpoint.checkpointState.sessions)
        val messagesByType = sourceSessionIdToMessage.toList()
                .map { (sourceSessionId, message) -> Triple(sourceSessionId, checkpoint.checkpointState.sessions[sourceSessionId]!!, message) }
                .groupBy { it.second::class }

        val sendInitialActions = messagesByType[SessionState.Uninitiated::class]?.mapNotNull { (sourceSessionId, sessionState, message) ->
            val uninitiatedSessionState = sessionState as SessionState.Uninitiated
            val shardId = generateShardId(context.id.toString())
            if (sessionState.hasBeenAcknowledged != null) {
                newSessions[sourceSessionId] = SessionState.Initiated(
                        peerParty = sessionState.hasBeenAcknowledged.first,
                        peerFlowInfo = sessionState.hasBeenAcknowledged.second.initiatedFlowInfo,
                        receivedMessages = emptyMap(),
                        otherSideErrored = false,
                        peerSinkSessionId = sessionState.hasBeenAcknowledged.second.initiatedSessionId,
                        deduplicationSeed = sessionState.deduplicationSeed,
                        nextSendingSeqNumber = 1,
                        lastProcessedSeqNumber = 0,
                        shardId = shardId,
                        lastSenderUUID = null,
                        lastSenderSeqNo = null
                )
                null
            } else {
                newSessions[sourceSessionId] = SessionState.Initiating(
                        bufferedMessages = emptyList(),
                        rejectionError = null,
                        deduplicationSeed = sessionState.deduplicationSeed,
                        nextSendingSeqNumber = 1,
                        shardId = shardId,
                        receivedMessages = emptyMap(),
                        lastSenderUUID = null,
                        lastSenderSeqNo = null
                )
                val initialMessage = createInitialSessionMessage(uninitiatedSessionState.initiatingSubFlow, sourceSessionId, uninitiatedSessionState.additionalEntropy, message)
                val messageType = MessageType.inferFromMessage(initialMessage)
                val messageIdentifier = MessageIdentifier(messageType, shardId, sourceSessionId.calculateInitiatedSessionId(), 0, checkpoint.checkpointState.suspensionTime)
                Action.SendInitial(uninitiatedSessionState.destination, initialMessage, SenderDeduplicationInfo(messageIdentifier, startingState.senderUUID))
            }
        } ?: emptyList()
        messagesByType[SessionState.Initiating::class]?.forEach { (sourceSessionId, sessionState, message) ->
            val initiatingSessionState = sessionState as SessionState.Initiating
            val sessionMessage = DataSessionMessage(message)
            val messageIdentifier = MessageIdentifier(MessageType.DATA_MESSAGE, sessionState.shardId, sourceSessionId.calculateInitiatedSessionId(), sessionState.nextSendingSeqNumber, checkpoint.checkpointState.suspensionTime)
            newSessions[sourceSessionId] = initiatingSessionState.bufferMessage(messageIdentifier, sessionMessage)
        }
        val sendExistingActions = messagesByType[SessionState.Initiated::class]?.map {(sourceSessionId, sessionState, message) ->
            val initiatedSessionState = sessionState as SessionState.Initiated
            val sessionMessage = DataSessionMessage(message)
            val sinkSessionId = initiatedSessionState.peerSinkSessionId
            val existingMessage = ExistingSessionMessage(sinkSessionId, sessionMessage)
            val messageType = MessageType.inferFromMessage(existingMessage)
            val messageIdentifier = MessageIdentifier(messageType, sessionState.shardId, sessionState.peerSinkSessionId, sessionState.nextSendingSeqNumber, checkpoint.checkpointState.suspensionTime)
            newSessions[sourceSessionId] = initiatedSessionState.copy(nextSendingSeqNumber = initiatedSessionState.nextSendingSeqNumber + 1)
            Action.SendExisting(initiatedSessionState.peerParty, existingMessage, SenderDeduplicationInfo(messageIdentifier, startingState.senderUUID))
        } ?: emptyList()

        if (sendInitialActions.isNotEmpty() || sendExistingActions.isNotEmpty()) {
            actions.add(Action.SendMultiple(sendInitialActions, sendExistingActions))
        }
        currentState = currentState.copy(checkpoint = checkpoint.setSessions(newSessions))
    }

    private fun sessionToSessionId(session: FlowSession): SessionId {
        return (session as FlowSessionImpl).sourceSessionId
    }

    private fun collectErroredSessionErrors(startingState: StateMachineState, sessionIds: Collection<SessionId>): Pair<StateMachineState, List<Throwable>> {
        var newState = startingState
        val errors = sessionIds.filter { sessionId ->
                    startingState.checkpoint.checkpointState.sessions.containsKey(sessionId)
                }.flatMap { sessionId ->
                    val sessionState = startingState.checkpoint.checkpointState.sessions[sessionId]!!
                    when (sessionState) {
                        is SessionState.Uninitiated -> emptyList()
                        is SessionState.Initiating -> {
                            if (sessionState.rejectionError == null) {
                                emptyList()
                            } else {
                                listOf(sessionState.rejectionError.exception)
                            }
                        }
                        is SessionState.Initiated -> {
                            if (sessionState.hasErrored()) {
                                val (message, newSessionState) = sessionState.extractMessage()
                                val exception = convertErrorMessageToException(message as ErrorSessionMessage, sessionState.peerParty)
                                val newCheckpoint = startingState.checkpoint.addSession(sessionId to newSessionState.copy(otherSideErrored = true))
                                newState = startingState.copy(checkpoint = newCheckpoint)
                                listOf(exception)
                            } else {
                                emptyList()
                            }
                        }
                    }
                }
        return Pair(newState, errors)
    }

    private fun convertErrorMessageToException(errorMessage: ErrorSessionMessage, peer: Party): Throwable {
        return if (errorMessage.flowException == null) {
            UnexpectedFlowEndException("Counter-flow errored", cause = null, originalErrorId = errorMessage.errorId).apply {
                DeclaredField<Party?>(
                    UnexpectedFlowEndException::class.java,
                    "peer",
                    this
                ).value = peer
            }
        } else {
            errorMessage.flowException.apply {
                originalErrorId = errorMessage.errorId
                DeclaredField<Party?>(FlowException::class.java, "peer", errorMessage.flowException).value = peer
            }
        }
    }

    private fun collectUncloseableSessions(sessionIds: Collection<SessionId>, checkpoint: Checkpoint): List<Throwable> {
        val uninitialisedSessions = sessionIds.mapNotNull { sessionId ->
                    if (!checkpoint.checkpointState.sessions.containsKey(sessionId))
                        null
                    else
                        sessionId to checkpoint.checkpointState.sessions[sessionId]
                }
                .filter { (_, sessionState) -> sessionState !is SessionState.Initiated }
                .map { it.first }

        return uninitialisedSessions.map { PrematureSessionCloseException(it) }
    }

    private fun collectErroredInitiatingSessionErrors(checkpoint: Checkpoint): List<Throwable> {
        return checkpoint.checkpointState.sessions.values.mapNotNull { sessionState ->
            (sessionState as? SessionState.Initiating)?.rejectionError?.exception
        }
    }

    private fun collectEndedSessionErrors(sessionIds: Collection<SessionId>, checkpoint: Checkpoint): List<Throwable> {
        return sessionIds.filter { sessionId ->
            sessionId !in checkpoint.checkpointState.sessions
        }.map {sessionId ->
            UnexpectedFlowEndException(
                    "Tried to access ended session $sessionId",
                    cause = null,
                    originalErrorId = context.secureRandom.nextLong()
            )
        }
    }

    private fun collectRelevantErrorsToThrow(startingState: StateMachineState, flowIORequest: FlowIORequest<*>): Pair<StateMachineState, List<Throwable>> {
        return when (flowIORequest) {
            is FlowIORequest.Send -> {
                val sessionIds = flowIORequest.sessionToMessage.keys.map(this::sessionToSessionId)
                val (newState, erroredSessionErrors) = collectErroredSessionErrors(startingState, sessionIds)
                val endedSessionErrors = collectEndedSessionErrors(sessionIds, startingState.checkpoint)
                Pair(newState, erroredSessionErrors + endedSessionErrors)
            }
            is FlowIORequest.Receive -> {
                val sessionIds = flowIORequest.sessions.map(this::sessionToSessionId)
                val (newState, erroredSessionErrors) = collectErroredSessionErrors(startingState, sessionIds)
                val endedSessionErrors = collectEndedSessionErrors(sessionIds, startingState.checkpoint)
                Pair(newState, erroredSessionErrors + endedSessionErrors)
            }
            is FlowIORequest.SendAndReceive -> {
                val sessionIds = flowIORequest.sessionToMessage.keys.map(this::sessionToSessionId)
                val (newState, erroredSessionErrors) = collectErroredSessionErrors(startingState, sessionIds)
                val endedSessionErrors = collectEndedSessionErrors(sessionIds, startingState.checkpoint)
                Pair(newState, erroredSessionErrors + endedSessionErrors)
            }
            is FlowIORequest.WaitForLedgerCommit -> {
                return collectErroredSessionErrors(startingState, startingState.checkpoint.checkpointState.sessions.keys)
            }
            is FlowIORequest.GetFlowInfo -> {
                val sessionIds = flowIORequest.sessions.map(this::sessionToSessionId)
                val (newState, erroredSessionErrors) = collectErroredSessionErrors(startingState, sessionIds)
                val endedSessionErrors = collectEndedSessionErrors(sessionIds, startingState.checkpoint)
                Pair(newState, erroredSessionErrors + endedSessionErrors)
            }
            is FlowIORequest.CloseSessions -> {
                val sessionIds = flowIORequest.sessions.map(this::sessionToSessionId)
                val (newState, erroredSessionErrors) = collectErroredSessionErrors(startingState, sessionIds)
                val uncloseableSessionErrors = collectUncloseableSessions(sessionIds, startingState.checkpoint)
                Pair(newState, erroredSessionErrors + uncloseableSessionErrors)
            }
            is FlowIORequest.Sleep -> {
                Pair(startingState, emptyList())
            }
            is FlowIORequest.WaitForSessionConfirmations -> {
                val errors = collectErroredInitiatingSessionErrors(startingState.checkpoint)
                Pair(startingState, errors)
            }
            is FlowIORequest.ExecuteAsyncOperation<*> -> {
                Pair(startingState, emptyList())
            }
            FlowIORequest.ForceCheckpoint -> {
                Pair(startingState, emptyList())
            }
        }
    }

    private fun createInitialSessionMessage(
            initiatingSubFlow: SubFlow.Initiating,
            sourceSessionId: SessionId,
            additionalEntropy: Long,
            payload: SerializedBytes<Any>?
    ): InitialSessionMessage {
        return InitialSessionMessage(
                initiatorSessionId = sourceSessionId,
                // We add additional entropy to add to the initiated side's deduplication seed.
                initiationEntropy = additionalEntropy,
                initiatorFlowClassName = initiatingSubFlow.classToInitiateWith.name,
                flowVersion = initiatingSubFlow.flowInfo.flowVersion,
                appName = initiatingSubFlow.flowInfo.appName,
                firstPayload = payload
        )
    }

    private fun executeAsyncOperation(flowIORequest: FlowIORequest.ExecuteAsyncOperation<*>): TransitionResult {
        // This ensures that the [ExecuteAsyncOperation] request is not executed multiple times if extra
        // [DoRemainingWork] events are pushed onto the fiber's event queue before the flow has really woken up
        return if (!startingState.isWaitingForFuture) {
            builder {
                // The `numberOfSuspends` is added to the deduplication ID in case an async
                // operation is executed multiple times within the same flow.
                val deduplicationId = context.id.toString() + ":" + currentState.checkpoint.checkpointState.numberOfSuspends.toString()
                currentState = currentState.copy(isWaitingForFuture = true)
                actions += Action.ExecuteAsyncOperation(deduplicationId, flowIORequest.operation, currentState)
                FlowContinuation.ProcessEvents
            }
        } else {
            TransitionResult(startingState)
        }
    }

    private fun executeForceCheckpoint(): TransitionResult {
        return builder { resumeFlowLogic(Unit) }
    }

    private fun terminateSessionsIfRequired(transition: TransitionResult): TransitionResult {
        // If there are sessions to be closed, close them on the currently executing transition
        val sessionsToBeTerminated = findSessionsToBeTerminated(transition.newState)
        return if (sessionsToBeTerminated.isNotEmpty()) {
            val checkpointWithSessionsRemoved = transition.newState.checkpoint.removeSessions(sessionsToBeTerminated.keys)
            transition.copy(
                newState = transition.newState.copy(checkpoint = checkpointWithSessionsRemoved),
                actions = transition.actions + Action.RemoveSessionBindings(sessionsToBeTerminated.keys)
            )
        } else {
            transition
        }
    }

    private fun findSessionsToBeTerminated(startingState: StateMachineState): SessionMap {
        return startingState.checkpoint.checkpointState.sessionsToBeClosed.mapNotNull { sessionId ->
            val sessionState = startingState.checkpoint.checkpointState.sessions[sessionId]!!
            if (sessionState is SessionState.Initiated && sessionState.receivedMessages.containsKey(sessionState.lastProcessedSeqNumber + 1) && sessionState.receivedMessages[sessionState.lastProcessedSeqNumber + 1] is EndSessionMessage) {
                sessionId to sessionState
            } else {
                null
            }
        }.toMap()
    }
}
