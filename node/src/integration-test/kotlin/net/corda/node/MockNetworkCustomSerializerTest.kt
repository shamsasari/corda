package net.corda.node

import net.corda.contracts.serialization.custom.Currantsy
import net.corda.core.contracts.TransactionVerificationException.ContractRejection
import net.corda.flows.serialization.custom.CustomSerializerFlow
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.internal.cordappWithPackages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import kotlin.test.assertFailsWith

@Suppress("FunctionName")
class MockNetworkCustomSerializerTest {
    companion object {
        private const val CURRANTS = 5000L

        @JvmField
        val currantsy = Currantsy(CURRANTS)

        @BeforeAll
        @JvmStatic
        fun checkData() {
            assertNotCordaSerializable<Currantsy>()
        }
    }

    private lateinit var mockNetwork: MockNetwork

    @BeforeEach
    fun setup() {
        mockNetwork = MockNetwork(
            MockNetworkParameters(
                networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
                cordappsForAllNodes = listOf(
                    cordappWithPackages("net.corda.flows.serialization.custom").signed(),
                    cordappWithPackages("net.corda.contracts.serialization.custom").signed()
                )
            )
        )
    }

    @AfterEach
    fun shutdown() {
        mockNetwork.stopNodes()
    }

    @Test
    fun `flow with custom serializer mock network`() {
        val a = mockNetwork.createPartyNode()
        val ex = assertFailsWith<ExecutionException> {
            a.startFlow(CustomSerializerFlow(currantsy)).get()
        }
        assertThat(ex)
            .hasCauseExactlyInstanceOf(ContractRejection::class.java)
            .hasMessageContaining("Too many currants! $currantsy is unraisinable!")
    }
}