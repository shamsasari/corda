package net.corda.coretests.serialization

import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.sha256
import net.corda.core.flows.NotaryError
import net.corda.core.flows.NotaryException
import net.corda.core.flows.StateConsumptionDetails
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.testing.core.SerializationEnvironmentRule
import org.junit.Rule
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotaryExceptionSerializationTest {
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    @Test
	fun testSerializationRoundTrip() {
        val txhash = SecureHash.randomSHA256()
        val stateHistory: Map<StateRef, StateConsumptionDetails> = mapOf(
                StateRef(txhash, 0) to StateConsumptionDetails(txhash.sha256())
        )
        val error = NotaryError.Conflict(txhash, stateHistory)
        val instance = NotaryException(error)
        val instanceOnTheOtherSide = instance.serialize().bytes.deserialize<NotaryException>()

        assertEquals(instance.error, instanceOnTheOtherSide.error)
        assertTrue(instance.error.toString().contains("->"))
    }
}